#!/usr/bin/env python3
"""Model (b): TF-IDF (word 1-2grams + char 3-5grams) + logistic regression.

Threshold is picked via out-of-fold predictions on the TRAIN set only (GroupKFold by
source, so no video leaks between folds), targeting recall ~0.9. The final model is
then refit on all of train and evaluated once on the held-out test set (whole videos
never seen during training or threshold selection).

Exports a compact JSON (vocab + idf + logistic-regression weights) to
out/tfidf_lr.json, small enough to reimplement in pure Kotlin: tokenize with
heuristic.normalize() (already a straight port of the on-device
RepeatedClaimMatcher.normalize), compute sklearn's smooth TF-IDF per sub-vectorizer
(word, char), L2-normalize each block, concatenate, dot with `coef`, add `intercept`,
compare to `threshold`.
"""
import csv
import sys

import numpy as np
from scipy.sparse import hstack
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import GroupKFold, cross_val_predict

from heuristic import normalize
from metrics_util import pick_threshold_for_recall, precision_recall_f1, save_json

WORD_NGRAMS = (1, 2)
CHAR_NGRAMS = (3, 5)
MAX_WORD_FEATURES = 2500
MAX_CHAR_FEATURES = 3000
TARGET_RECALL = 0.9


def load(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    texts = [normalize(r["normalized"]) for r in rows]
    labels = [int(r["label"]) for r in rows]
    sources = [r["source"] for r in rows]
    return texts, labels, sources


def build_vectorizers():
    word_vec = TfidfVectorizer(
        analyzer="word", token_pattern=r"[^ ]+", ngram_range=WORD_NGRAMS,
        max_features=MAX_WORD_FEATURES, sublinear_tf=True, min_df=2,
    )
    char_vec = TfidfVectorizer(
        analyzer="char", ngram_range=CHAR_NGRAMS,
        max_features=MAX_CHAR_FEATURES, sublinear_tf=True, min_df=2,
    )
    return word_vec, char_vec


def features(word_vec, char_vec, texts, fit=False):
    if fit:
        xw = word_vec.fit_transform(texts)
        xc = char_vec.fit_transform(texts)
    else:
        xw = word_vec.transform(texts)
        xc = char_vec.transform(texts)
    return hstack([xw, xc]).tocsr()


def main():
    train_texts, train_y, train_sources = load("data/train.csv")
    test_texts, test_y, _ = load("data/test.csv")
    train_y = np.array(train_y)
    test_y = np.array(test_y)

    # --- Out-of-fold scores on train, to pick a threshold without touching test ---
    n_groups = min(5, len(set(train_sources)))
    gkf = GroupKFold(n_splits=n_groups)
    oof_scores = np.zeros(len(train_texts))
    for fold_i, (tr_idx, va_idx) in enumerate(
        gkf.split(np.zeros(len(train_texts)), train_y, groups=train_sources)
    ):
        wv, cv = build_vectorizers()
        xtr = features(wv, cv, [train_texts[i] for i in tr_idx], fit=True)
        xva = features(wv, cv, [train_texts[i] for i in va_idx], fit=False)
        clf = LogisticRegression(max_iter=2000, class_weight="balanced", C=1.0)
        clf.fit(xtr, train_y[tr_idx])
        oof_scores[va_idx] = clf.predict_proba(xva)[:, 1]

    thr, oof_metrics = pick_threshold_for_recall(list(train_y), list(oof_scores), TARGET_RECALL)
    print(f"OOF threshold={thr:.3f} -> {oof_metrics}", file=sys.stderr)

    # --- Final model: fit on all of train, evaluate once on test ---
    word_vec, char_vec = build_vectorizers()
    x_train = features(word_vec, char_vec, train_texts, fit=True)
    x_test = features(word_vec, char_vec, test_texts, fit=False)
    clf = LogisticRegression(max_iter=2000, class_weight="balanced", C=1.0)
    clf.fit(x_train, train_y)

    test_scores = clf.predict_proba(x_test)[:, 1]
    test_pred = [1 if s >= thr else 0 for s in test_scores]
    test_metrics = precision_recall_f1(list(test_y), test_pred)
    print(f"TEST @thr={thr:.3f} (n={len(test_y)}): {test_metrics}", file=sys.stderr)

    # Also report test metrics at the default 0.5 threshold for reference.
    default_pred = [1 if s >= 0.5 else 0 for s in test_scores]
    default_metrics = precision_recall_f1(list(test_y), default_pred)
    print(f"TEST @thr=0.5 (n={len(test_y)}): {default_metrics}", file=sys.stderr)

    save_json(
        {
            "model": "tfidf_logreg",
            "threshold": thr,
            "oof_train_metrics_at_threshold": oof_metrics,
            "test_metrics_at_threshold": test_metrics,
            "test_metrics_at_0.5": default_metrics,
            "n_train": len(train_texts),
            "n_test": len(test_texts),
        },
        "out/metrics_tfidf.json",
    )

    export_kotlin_json(word_vec, char_vec, clf, thr)


def export_kotlin_json(word_vec, char_vec, clf, threshold):
    n_word = len(word_vec.vocabulary_)
    coef = clf.coef_[0]
    export = {
        "preprocessing": (
            "Apply RepeatedClaimMatcher.normalize(sentence) first (lowercase, NFD "
            "accent-strip, drop punctuation except '%' and ',', collapse thousands "
            "separators, collapse whitespace)."
        ),
        "word": {
            "ngram_range": list(WORD_NGRAMS),
            "tokenizer": "split normalized text on ' ', then build word n-grams joined by a single space",
            "vocab": {tok: int(idx) for tok, idx in word_vec.vocabulary_.items()},
            "idf": [float(v) for v in word_vec.idf_],
        },
        "char": {
            "ngram_range": list(CHAR_NGRAMS),
            "tokenizer": "character n-grams over the whole normalized string (including spaces)",
            "vocab": {tok: int(idx) for tok, idx in char_vec.vocabulary_.items()},
            "idf": [float(v) for v in char_vec.idf_],
        },
        "scoring": (
            "score(x) = sigmoid(intercept + dot(coef, concat(l2_normalize(tfidf_word(x)), "
            "l2_normalize(tfidf_char(x))))); tfidf(t) = (1+ln(tf)) * idf[t] for tf>0, else 0; "
            "predict Claim iff score(x) >= threshold."
        ),
        "coef": [float(c) for c in coef],
        "coef_word_len": n_word,
        "coef_char_len": len(coef) - n_word,
        "intercept": float(clf.intercept_[0]),
        "threshold": float(threshold),
    }
    save_json(export, "out/tfidf_lr_export.json")
    print("Exported out/tfidf_lr_export.json", file=sys.stderr)


if __name__ == "__main__":
    main()
