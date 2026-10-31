#!/usr/bin/env python3
"""Model (c): fine-tune a small French transformer for phone-sized on-device inference.

Model: Geotrend/distilbert-base-fr-cased (Apache-2.0, 62.4M params, DistilBERT
architecture with a French-pruned vocabulary derived from mBERT -- much smaller than a
multilingual model while keeping the same representations for French tokens).

CPU-only fine-tune. To pick a recall~0.9 threshold without a second training pass, train
on a source-held-out 85% of `data/train.csv` ("fit"), tune the threshold on the
remaining 15% ("val", different videos again), then evaluate once on `data/test.csv`.
"""
import csv
import random
import sys

import numpy as np
import torch
from torch.utils.data import Dataset
from transformers import (
    AutoModelForSequenceClassification,
    AutoTokenizer,
    Trainer,
    TrainingArguments,
)

from metrics_util import pick_threshold_for_recall, precision_recall_f1, save_json

MODEL_NAME = "Geotrend/distilbert-base-fr-cased"
MAX_LEN = 64
TARGET_RECALL = 0.9
SEED = 42
OUT_DIR = "out/transformer_model"


def load(path):
    return list(csv.DictReader(open(path, encoding="utf-8")))


def split_by_source(rows, val_fraction=0.15, seed=SEED):
    by_source = {}
    for r in rows:
        by_source.setdefault(r["source"], []).append(r)
    sources = list(by_source.keys())
    rng = random.Random(seed)
    rng.shuffle(sources)
    target_val = len(rows) * val_fraction
    val_sources, running = [], 0
    for s in sources:
        if running < target_val:
            val_sources.append(s)
            running += len(by_source[s])
    val_rows = [r for s in val_sources for r in by_source[s]]
    fit_rows = [r for r in rows if r["source"] not in val_sources]
    return fit_rows, val_rows


class SentenceDataset(Dataset):
    def __init__(self, rows, tokenizer):
        # The on-device filter only ever sees STT-shaped text, so train on `normalized`.
        self.texts = [r["normalized"] for r in rows]
        self.labels = [int(r["label"]) for r in rows]
        self.enc = tokenizer(self.texts, truncation=True, max_length=MAX_LEN, padding="max_length")

    def __len__(self):
        return len(self.labels)

    def __getitem__(self, idx):
        item = {k: torch.tensor(v[idx]) for k, v in self.enc.items()}
        item["labels"] = torch.tensor(self.labels[idx])
        return item


def main():
    train_rows = load("data/train.csv")
    test_rows = load("data/test.csv")
    fit_rows, val_rows = split_by_source(train_rows)
    print(f"fit={len(fit_rows)} val={len(val_rows)} test={len(test_rows)}", file=sys.stderr)

    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_NAME, num_labels=2)

    fit_ds = SentenceDataset(fit_rows, tokenizer)
    val_ds = SentenceDataset(val_rows, tokenizer)
    test_ds = SentenceDataset(test_rows, tokenizer)

    args = TrainingArguments(
        output_dir="out/transformer_ckpt",
        num_train_epochs=4,
        per_device_train_batch_size=16,
        per_device_eval_batch_size=32,
        learning_rate=3e-5,
        weight_decay=0.01,
        logging_steps=20,
        save_strategy="no",
        eval_strategy="no",
        report_to=[],
        seed=SEED,
    )
    trainer = Trainer(model=model, args=args, train_dataset=fit_ds)
    trainer.train()

    def scores_for(ds):
        preds = trainer.predict(ds)
        logits = preds.predictions
        probs = torch.softmax(torch.tensor(logits), dim=1)[:, 1].numpy()
        return probs

    val_scores = scores_for(val_ds)
    val_labels = [int(r["label"]) for r in val_rows]
    thr, val_metrics = pick_threshold_for_recall(val_labels, list(val_scores), TARGET_RECALL)
    print(f"VAL threshold={thr:.3f} -> {val_metrics}", file=sys.stderr)

    test_scores = scores_for(test_ds)
    test_labels = [int(r["label"]) for r in test_rows]
    test_pred = [1 if s >= thr else 0 for s in test_scores]
    test_metrics = precision_recall_f1(test_labels, test_pred)
    print(f"TEST @thr={thr:.3f} (n={len(test_labels)}): {test_metrics}", file=sys.stderr)

    default_pred = [1 if s >= 0.5 else 0 for s in test_scores]
    default_metrics = precision_recall_f1(test_labels, default_pred)
    print(f"TEST @thr=0.5 (n={len(test_labels)}): {default_metrics}", file=sys.stderr)

    save_json(
        {
            "model": MODEL_NAME,
            "threshold": float(thr),
            "val_metrics_at_threshold": val_metrics,
            "test_metrics_at_threshold": test_metrics,
            "test_metrics_at_0.5": default_metrics,
            "n_fit": len(fit_rows), "n_val": len(val_rows), "n_test": len(test_rows),
        },
        "out/metrics_transformer.json",
    )

    model.save_pretrained(OUT_DIR)
    tokenizer.save_pretrained(OUT_DIR)
    print(f"Saved fine-tuned model to {OUT_DIR}", file=sys.stderr)


if __name__ == "__main__":
    main()
