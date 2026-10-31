#!/usr/bin/env python3
"""Export the fine-tuned transformer (out/transformer_model) to ONNX, dynamically
quantize it to int8, measure on-disk size and single-sentence CPU latency, and save the
tokenizer files next to it for the Android side."""
import json
import shutil
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic
from optimum.onnxruntime import ORTModelForSequenceClassification
from transformers import AutoTokenizer

MODEL_DIR = "out/transformer_model"
ONNX_DIR = Path("out/transformer_onnx")
FP32_PATH = ONNX_DIR / "model.onnx"
INT8_PATH = ONNX_DIR / "model.int8.onnx"
MAX_LEN = 64
N_LATENCY_RUNS = 50


def export_onnx():
    # optimum handles the transformers -> ONNX tracing correctly (dict-based inputs,
    # dynamic axes, opset) across torch/transformers version churn; a raw
    # torch.onnx.export call broke on torch 2.14's new dynamo-based exporter.
    ONNX_DIR.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(MODEL_DIR)
    ort_model = ORTModelForSequenceClassification.from_pretrained(MODEL_DIR, export=True)
    ort_model.save_pretrained(str(ONNX_DIR))
    tokenizer.save_pretrained(str(ONNX_DIR))
    exported = ONNX_DIR / "model.onnx"
    if exported != FP32_PATH:
        exported.rename(FP32_PATH)
    print(f"Exported fp32 ONNX to {FP32_PATH}", file=sys.stderr)
    enc = tokenizer("exemple", return_tensors="np")
    return list(enc.keys())


def quantize():
    quantize_dynamic(str(FP32_PATH), str(INT8_PATH), weight_type=QuantType.QInt8)
    print(f"Quantized int8 ONNX to {INT8_PATH}", file=sys.stderr)


def measure(input_names):
    tokenizer = AutoTokenizer.from_pretrained(str(ONNX_DIR))
    sess = ort.InferenceSession(str(INT8_PATH), providers=["CPUExecutionProvider"])
    sess_input_names = {i.name for i in sess.get_inputs()}
    sentence = "LE CHOMAGE A AUGMENTE DE DIX POURCENT DEPUIS DEUX MILLE VINGT QUATRE"
    enc = tokenizer(sentence, return_tensors="np", padding="max_length", truncation=True, max_length=MAX_LEN)
    feed = {k: enc[k].astype(np.int64) for k in enc if k in sess_input_names}
    # warmup
    for _ in range(5):
        sess.run(None, feed)
    times = []
    for _ in range(N_LATENCY_RUNS):
        t0 = time.perf_counter()
        sess.run(None, feed)
        times.append(time.perf_counter() - t0)
    return {
        "latency_ms_mean": float(np.mean(times) * 1000),
        "latency_ms_p95": float(np.percentile(times, 95) * 1000),
    }


def main():
    input_names = export_onnx()
    quantize()
    fp32_size = FP32_PATH.stat().st_size
    int8_size = INT8_PATH.stat().st_size
    latency = measure(input_names)
    report = {
        "fp32_size_mb": fp32_size / 1e6,
        "int8_size_mb": int8_size / 1e6,
        **latency,
    }
    print(json.dumps(report, indent=2), file=sys.stderr)
    with open("out/metrics_transformer_onnx.json", "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    # fp32 graph no longer needed once we have the quantized weights
    FP32_PATH.unlink(missing_ok=True)
    for extra in ONNX_DIR.glob("model.onnx.data"):
        extra.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
