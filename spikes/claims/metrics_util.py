"""Shared metric helpers: precision/recall/F1 for the positive class, and a threshold
picker that favours a target recall (~0.9), used by every model's evaluation script."""
import json


def precision_recall_f1(y_true, y_pred):
    tp = sum(1 for t, p in zip(y_true, y_pred) if t == 1 and p == 1)
    fp = sum(1 for t, p in zip(y_true, y_pred) if t == 0 and p == 1)
    fn = sum(1 for t, p in zip(y_true, y_pred) if t == 1 and p == 0)
    precision = tp / (tp + fp) if (tp + fp) else 0.0
    recall = tp / (tp + fn) if (tp + fn) else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0.0
    return {"precision": precision, "recall": recall, "f1": f1, "tp": tp, "fp": fp, "fn": fn}


def pick_threshold_for_recall(y_true, scores, target_recall=0.9):
    """Among thresholds achieving recall >= target_recall on (y_true, scores), return
    the one with the highest precision (ties broken by higher threshold = more
    conservative). Falls back to the threshold with the recall closest to target if
    none reaches it."""
    candidates = sorted(set(scores))
    best = None
    best_key = None
    fallback = None
    fallback_key = None
    for thr in candidates:
        preds = [1 if s >= thr else 0 for s in scores]
        m = precision_recall_f1(y_true, preds)
        key = (m["precision"], thr)
        if m["recall"] >= target_recall:
            if best_key is None or key > best_key:
                best_key = key
                best = (thr, m)
        fb_key = (-abs(m["recall"] - target_recall), m["precision"])
        if fallback_key is None or fb_key > fallback_key:
            fallback_key = fb_key
            fallback = (thr, m)
    return best if best is not None else fallback


def save_json(obj, path):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
