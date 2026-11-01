"""Train a real CPU classifier and track its measured results with LabFlow.

The digits dataset is bundled with scikit-learn. No dataset download or GPU is needed.
Install: python -m pip install -e './sdk[examples]'
Run: python sdk/examples/train_digits.py
"""

import argparse
import getpass
import os
import platform
import sys
from dataclasses import dataclass

import numpy as np
import sklearn
from sklearn.datasets import load_digits
from sklearn.linear_model import SGDClassifier
from sklearn.metrics import accuracy_score, classification_report, log_loss
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler

from labflow import Client, LabflowError, Run


@dataclass(frozen=True)
class TrainingConfig:
    epochs: int = 20
    batch_size: int = 64
    learning_rate: float = 0.01
    seed: int = 42
    fail_after_epoch: int | None = None


@dataclass(frozen=True)
class TrainingResult:
    test_loss: float
    test_accuracy: float
    steps: int


def train(run: Run, config: TrainingConfig) -> TrainingResult:
    """Fit preprocessing only on training data; evaluate held-out test data once."""
    digits = load_digits()
    train_x, test_x, train_y, test_y = train_test_split(
        digits.data, digits.target, test_size=0.2, stratify=digits.target, random_state=config.seed
    )
    train_x, val_x, train_y, val_y = train_test_split(
        train_x, train_y, test_size=0.2, stratify=train_y, random_state=config.seed
    )
    scaler = StandardScaler().fit(train_x)
    train_x, val_x, test_x = (scaler.transform(data) for data in (train_x, val_x, test_x))
    classes = np.arange(10)
    model = SGDClassifier(
        loss="log_loss",
        penalty="l2",
        alpha=0.0001,
        learning_rate="constant",
        eta0=config.learning_rate,
        shuffle=False,  # Shuffle batches explicitly with a reproducible RNG each epoch.
        random_state=config.seed,
        average=True,
    )
    rng = np.random.default_rng(config.seed)
    run.params(
        {
            "model": "SGDClassifier",
            "loss": "log_loss",
            "epochs": config.epochs,
            "batch_size": config.batch_size,
            "learning_rate": config.learning_rate,
            "alpha": 0.0001,
            "seed": config.seed,
            "average": True,
            "preprocessing": "StandardScaler(train_only)",
            "train_samples": len(train_y),
            "validation_samples": len(val_y),
            "test_samples": len(test_y),
            "features": train_x.shape[1],
        }
    )
    run.log(f"Training started: train={len(train_y)}, validation={len(val_y)}, test={len(test_y)}")
    step = 0
    for epoch in range(1, config.epochs + 1):
        order = rng.permutation(len(train_y))
        for offset in range(0, len(order), config.batch_size):
            batch = order[offset : offset + config.batch_size]
            model.partial_fit(train_x[batch], train_y[batch], classes=classes)
            step += 1
        train_probs = model.predict_proba(train_x)
        val_probs = model.predict_proba(val_x)
        metrics = {
            "train_loss": float(log_loss(train_y, train_probs, labels=classes)),
            "train_accuracy": float(accuracy_score(train_y, model.predict(train_x))),
            "val_loss": float(log_loss(val_y, val_probs, labels=classes)),
            "val_accuracy": float(accuracy_score(val_y, model.predict(val_x))),
        }
        run.metric(metrics, step=step, epoch=epoch)
        progress = (
            f"epoch={epoch:02d}/{config.epochs} step={step} "
            f"train_loss={metrics['train_loss']:.4f} val_loss={metrics['val_loss']:.4f} "
            f"train_accuracy={metrics['train_accuracy']:.4f} "
            f"val_accuracy={metrics['val_accuracy']:.4f}"
        )
        print(progress, flush=True)
        run.log(progress)
        if config.fail_after_epoch == epoch:
            raise RuntimeError(f"Intentional training failure after epoch {epoch}")

    test_probs = model.predict_proba(test_x)
    test_predictions = model.predict(test_x)
    result = TrainingResult(
        test_loss=float(log_loss(test_y, test_probs, labels=classes)),
        test_accuracy=float(accuracy_score(test_y, test_predictions)),
        steps=step,
    )
    run.metric(
        {"test_loss": result.test_loss, "test_accuracy": result.test_accuracy},
        step=step,
        epoch=config.epochs,
    )
    run.text(
        "classification_report",
        classification_report(test_y, test_predictions, labels=classes, digits=4, zero_division=0),
        step=step,
    )
    samples = [
        f"sample={index} actual={int(test_y[index])} predicted={int(test_predictions[index])} "
        f"confidence={float(test_probs[index].max()):.4f}"
        for index in range(min(12, len(test_y)))
    ]
    run.text("prediction_samples", "\n".join(samples), step=step)
    run.log(f"Training finished: test_accuracy={result.test_accuracy:.4f}")
    return result


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", default="digits-sgd-training", help="LabFlow run name")
    parser.add_argument("--base-url", default=None, help="Backend root; default: LABFLOW_BASE_URL")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--learning-rate", type=float, default=0.01)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--fail-after-epoch", type=int, help="Intentionally fail after this epoch")
    args = parser.parse_args()
    if not 1 <= args.epochs <= 1000:
        parser.error("--epochs must be between 1 and 1000")
    if args.batch_size < 1:
        parser.error("--batch-size must be positive")
    if not np.isfinite(args.learning_rate) or args.learning_rate <= 0:
        parser.error("--learning-rate must be positive and finite")
    if not 0 <= args.seed <= 2**32 - 1:
        parser.error("--seed must be between 0 and 4294967295")
    if args.fail_after_epoch is not None and not 1 <= args.fail_after_epoch <= args.epochs:
        parser.error("--fail-after-epoch must be between 1 and --epochs")
    return args


def main() -> int:
    args = arguments()
    api_key = os.getenv("LABFLOW_API_KEY")
    if not api_key:
        if not sys.stdin.isatty():
            print(
                "Set LABFLOW_API_KEY, or run interactively to enter the key securely.",
                file=sys.stderr,
            )
            return 2
        api_key = getpass.getpass("Project API key (hidden): ")
    config = TrainingConfig(
        epochs=args.epochs,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
        seed=args.seed,
        fail_after_epoch=args.fail_after_epoch,
    )
    try:
        with Client(api_key=api_key, base_url=args.base_url) as client:
            with client.start_run(
                args.name,
                metadata={
                    "framework": "scikit-learn",
                    "model": "SGDClassifier",
                    "dataset": "digits",
                    "device": "cpu",
                    "python_version": platform.python_version(),
                    "sklearn_version": sklearn.__version__,
                    "numpy_version": np.__version__,
                },
            ) as run:
                print(f"Run ID: {run.id}", flush=True)
                result = train(run, config)
            status = client.get_run(run.id)
            print(
                f"Run {run.id}: {status['status']} | test_accuracy={result.test_accuracy:.4f} "
                f"test_loss={result.test_loss:.4f}",
                flush=True,
            )
            print(
                "Paste the Run ID into the frontend API test's Run ID field and query its status."
            )
        return 0
    except (LabflowError, RuntimeError) as error:
        print(
            f"{type(error).__name__}: {str(error).replace(api_key, '[REDACTED]')}", file=sys.stderr
        )
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
