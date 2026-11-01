"""Run after setting LABFLOW_API_KEY and optionally LABFLOW_BASE_URL."""

import labflow


def main() -> None:
    with labflow.init("sdk-example", metadata={"framework": "python"}) as run:
        run.params({"optimizer": "adam", "batch_size": 32})
        run.log("training started")
        for step in range(3):
            run.metric({"loss": 1.0 / (step + 1)}, step=step)
        run.text("prediction", "고양이가 창가에 앉아 있다.", step=2)
    print(f"completed run {run.id}")


if __name__ == "__main__":
    main()
