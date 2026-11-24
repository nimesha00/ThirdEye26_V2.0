"""Train the Third Eye Sri Lankan rupee note detector with MediaPipe Model Maker.

Produces lkr_notes.tflite (int8 via quantization-aware training, labels embedded in the model
metadata),
which the app loads from app/src/main/assets/lkr_notes.tflite.

Easiest in Google Colab (Runtime > Change runtime type > T4 GPU). In a Colab cell:

    !pip install -q mediapipe-model-maker
    # upload or mount your dataset (see README.md), then:
    !python train_lkr_notes.py --data /content/lkr_dataset --format voc

Model Maker does not install cleanly on Windows; use Colab or Linux/WSL with Python 3.9-3.11.

Expected dataset layout (README.md explains how to make it):

    lkr_dataset/
      train/       images + labels
      validation/  images + labels
      test/        images + labels   (optional but strongly recommended)

    --format voc : each split has images/ and Annotations/ (one Pascal VOC .xml per image)
    --format coco: each split has images/ and labels.json (COCO detection format)

Class names must be the note values: 20, 50, 100, 500, 1000, 5000. (Names such as
"1000_new_front" also work: the app reads the first number in the label.)
"""

import argparse
import os
import shutil

from mediapipe_model_maker import object_detector
from mediapipe_model_maker import quantization

EXPECTED_CLASSES = {"20", "50", "100", "500", "1000", "5000"}

# MediaPipe Model Maker's object detector offers SSD-style models with MobileNet backbones (the
# EfficientDet-Lite models belonged to the older, deprecated TFLite Model Maker). These are the
# same size class as EfficientDet-Lite0 / Lite2 and run in the app's MediaPipe ObjectDetector.
MODELS = {
    # 256x256 input: fastest, smallest. Good default; notes are usually held close.
    "multi_avg": object_detector.SupportedModels.MOBILENET_MULTI_AVG,
    # 384x384 input: slower, better for small or distant notes and several notes at once.
    "multi_avg_384": object_detector.SupportedModels.MOBILENET_MULTI_AVG_I384,
    # Plain MobileNetV2 alternatives.
    "mobilenet_v2": object_detector.SupportedModels.MOBILENET_V2,
    "mobilenet_v2_320": object_detector.SupportedModels.MOBILENET_V2_I320,
}


def load_split(path, fmt, cache_dir):
    if fmt == "voc":
        return object_detector.Dataset.from_pascal_voc_folder(path, cache_dir=cache_dir)
    return object_detector.Dataset.from_coco_folder(path, cache_dir=cache_dir)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", required=True, help="dataset root with train/ validation/ [test/]")
    parser.add_argument("--format", choices=["voc", "coco"], default="voc")
    parser.add_argument("--model", choices=sorted(MODELS), default="multi_avg")
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=0.3)
    parser.add_argument("--qat-epochs", type=int, default=15,
                        help="quantization-aware training epochs for the int8 export "
                             "(0 = skip QAT and export float16 instead)")
    parser.add_argument("--out", default="exported_model", help="output folder")
    args = parser.parse_args()

    cache = os.path.join(args.out, "cache")
    train = load_split(os.path.join(args.data, "train"), args.format, os.path.join(cache, "train"))
    val = load_split(os.path.join(args.data, "validation"), args.format, os.path.join(cache, "validation"))
    test_dir = os.path.join(args.data, "test")
    test = load_split(test_dir, args.format, os.path.join(cache, "test")) if os.path.isdir(test_dir) else None

    labels = [l for l in train.label_names if l != "background"]
    print("Classes found:", labels)
    print("Images: train", train.size, "validation", val.size, "test", test.size if test else 0)
    unexpected = set(labels) - EXPECTED_CLASSES
    missing = EXPECTED_CLASSES - {l.split("_")[0] for l in labels}
    if unexpected:
        print("Note: labels other than plain note values:", sorted(unexpected),
              "- fine if each starts with the note value, e.g. 1000_front.")
    if missing:
        print("WARNING: no examples for", sorted(missing), "- the app will never recognise these notes.")

    hparams = object_detector.HParams(
        export_dir=args.out,
        epochs=args.epochs,
        batch_size=args.batch_size,
        learning_rate=args.learning_rate,
    )
    options = object_detector.ObjectDetectorOptions(supported_model=MODELS[args.model], hparams=hparams)
    model = object_detector.ObjectDetector.create(train_data=train, validation_data=val, options=options)

    eval_data = test or val
    loss, coco = model.evaluate(eval_data, batch_size=args.batch_size)
    print("\nFloat model on", "test" if test else "validation", "set:")
    print_metrics(loss, coco)

    if args.qat_epochs > 0:
        # Quantization-aware training keeps accuracy close to the float model at int8 size.
        qat = object_detector.QATHParams(learning_rate=0.3, batch_size=args.batch_size,
                                         epochs=args.qat_epochs, decay_steps=6, decay_rate=0.96)
        model.quantization_aware_training(train, val, qat_hparams=qat)
        loss, coco = model.evaluate(eval_data, batch_size=args.batch_size)
        print("\nInt8 (QAT) model:")
        print_metrics(loss, coco)
        model.export_model("lkr_notes.tflite")
    else:
        # Model Maker's object detector gets int8 only through QAT; without it, export float16.
        model.export_model("lkr_notes.tflite",
                           quantization_config=quantization.QuantizationConfig.for_float16())

    exported = os.path.join(args.out, "lkr_notes.tflite")
    print("\nExported", exported, "(%.1f MB)" % (os.path.getsize(exported) / 1e6))
    print("Copy it to app/src/main/assets/lkr_notes.tflite and rebuild the app.")
    try:  # In Colab, offer the file for download.
        from google.colab import files  # type: ignore
        files.download(exported)
    except ImportError:
        pass
    shutil.rmtree(cache, ignore_errors=True)


def print_metrics(loss, coco):
    keys = ["AP", "AP50", "AP75", "APs", "APm", "APl", "ARmax1", "ARmax10", "ARmax100"]
    print("  loss:", loss)
    for k in keys:
        if k in coco:
            print("  %-9s %.3f" % (k, coco[k]))
    for k, v in sorted(coco.items()):
        if k.startswith("AP_/"):
            print("  per-class %-12s AP %.3f" % (k[4:], v))


if __name__ == "__main__":
    main()
