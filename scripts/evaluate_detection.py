#!/usr/bin/env python3
"""Evaluate fatigue/distraction detection results from a CSV file.

Expected columns:
  label,predicted,latency_ms

Labels can be NONE, FATIGUE, DISTRACTION, or BOTH. The script reports
multi-class accuracy plus binary alert precision/recall/false-positive rate.
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter
from pathlib import Path


CLASSES = ("NONE", "FATIGUE", "DISTRACTION", "BOTH")


def normalize(value: str) -> str:
    text = (value or "").strip().upper()
    if text not in CLASSES:
        raise ValueError(f"unsupported label: {value!r}")
    return text


def is_alert(value: str) -> bool:
    return value != "NONE"


def evaluate(path: Path) -> dict[str, object]:
    rows: list[tuple[str, str, float | None]] = []
    with path.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        for index, row in enumerate(reader, start=2):
            try:
                label = normalize(row.get("label", ""))
                predicted = normalize(row.get("predicted", ""))
            except ValueError as error:
                raise ValueError(f"{path}:{index}: {error}") from error

            raw_latency = (row.get("latency_ms") or "").strip()
            latency = float(raw_latency) if raw_latency else None
            rows.append((label, predicted, latency))

    if not rows:
        raise ValueError("input CSV has no data rows")

    confusion: Counter[tuple[str, str]] = Counter()
    correct = 0
    true_positive = false_positive = true_negative = false_negative = 0
    latencies: list[float] = []

    for label, predicted, latency in rows:
        confusion[(label, predicted)] += 1
        correct += int(label == predicted)
        if latency is not None:
            latencies.append(latency)

        expected_alert = is_alert(label)
        predicted_alert = is_alert(predicted)
        if expected_alert and predicted_alert:
            true_positive += 1
        elif not expected_alert and predicted_alert:
            false_positive += 1
        elif expected_alert and not predicted_alert:
            false_negative += 1
        else:
            true_negative += 1

    total = len(rows)
    precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else 0.0
    recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
    false_positive_rate = false_positive / (false_positive + true_negative) if false_positive + true_negative else 0.0
    avg_latency_ms = sum(latencies) / len(latencies) if latencies else None

    return {
        "samples": total,
        "accuracy": correct / total,
        "alert_precision": precision,
        "alert_recall": recall,
        "alert_false_positive_rate": false_positive_rate,
        "avg_latency_ms": avg_latency_ms,
        "confusion": confusion,
    }


def print_report(metrics: dict[str, object]) -> None:
    print(f"samples={metrics['samples']}")
    print(f"accuracy={metrics['accuracy']:.4f}")
    print(f"alert_precision={metrics['alert_precision']:.4f}")
    print(f"alert_recall={metrics['alert_recall']:.4f}")
    print(f"alert_false_positive_rate={metrics['alert_false_positive_rate']:.4f}")
    avg_latency_ms = metrics["avg_latency_ms"]
    print(f"avg_latency_ms={avg_latency_ms:.2f}" if avg_latency_ms is not None else "avg_latency_ms=NA")
    print("confusion_matrix:")
    confusion: Counter[tuple[str, str]] = metrics["confusion"]  # type: ignore[assignment]
    print("label,predicted,count")
    for label in CLASSES:
        for predicted in CLASSES:
            count = confusion[(label, predicted)]
            if count:
                print(f"{label},{predicted},{count}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate DriveEdge detection predictions.")
    parser.add_argument("--input", required=True, type=Path, help="CSV file with label,predicted,latency_ms columns")
    args = parser.parse_args()
    print_report(evaluate(args.input))


if __name__ == "__main__":
    main()
