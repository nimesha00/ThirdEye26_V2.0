# Sri Lankan rupee note detector (lkr_notes.tflite)

The app's note reader (`CurrencyDetection.java`) runs a MediaPipe object detector on the
camera picture and speaks what it finds, e.g. "This looks like a 1000 rupee note" or
"I can see 2 notes: 1000 and 500 rupees. Total 1500 rupees." The model is trained here, with
**MediaPipe Model Maker**, from photos you take. Until `app/src/main/assets/lkr_notes.tflite`
exists, the note reader says the model is missing and does nothing else.

Files:

- `train_lkr_notes.py` - training + evaluation + int8 export script.
- `train_lkr_notes.ipynb` - Google Colab notebook that installs Model Maker and runs the script.

> Model Maker is easiest in **Google Colab** (free T4 GPU). It does not install cleanly on
> Windows; if you want to run locally, use Linux or WSL2 with Python 3.9-3.11.
>
> Model Maker's object detector trains SSD models with MobileNet backbones (`multi_avg`, 256 px,
> default; `multi_avg_384` for better small-note accuracy). They are the same size class as
> EfficientDet-Lite0/Lite2, Apache-2.0 licensed, and load in the app's MediaPipe `ObjectDetector`.
> (EfficientDet-Lite training lived in the older TFLite Model Maker, which is deprecated.)

## 1. Classes

Six classes, named exactly by the note value:

| Class | Note |
|-------|------|
| `20`   | 20 rupees |
| `50`   | 50 rupees |
| `100`  | 100 rupees |
| `500`  | 500 rupees |
| `1000` | 1000 rupees |
| `5000` | 5000 rupees |

Each class must cover **both sides** of the note and **both the older and the newer note
series** that are still in circulation (e.g. the 2010 "Development, Prosperity and Sri Lankan
Dance" series and the 2011-2023 reprints / commemorative 1000 notes, if you can get them).
Label them all with the plain value; the model learns that both sides mean the same note.

If you want to analyse errors per side or series later, you may use names like `1000_front`,
`1000_old_back`: the app reads the **first number** in the label, so it still says "1000".
More classes need more photos, though, so start with the six plain values.

Coins are not covered.

## 2. Photo protocol

Aim for **150-300 photos per note value** (900-1800 total). More variety beats more of the
same photo. Use the phones blind users actually have, at the camera's normal resolution;
photos are resized during training, so don't crop them yourself.

For each note value, spread the photos roughly like this:

| Situation | Share | Notes |
|-----------|-------|-------|
| Flat, whole note, front | 15% | table, bed, floor, palm |
| Flat, whole note, back  | 15% | |
| Held in a hand | 20% | fingers covering a corner or edge, as a blind user would hold it |
| Folded in half / crumpled / worn | 15% | old, soft, torn-edge notes too |
| Partly covered or cut off at the frame edge | 10% | 30-70% of the note visible |
| Several notes together | 15% | fanned, overlapping, mixed values; label **every** visible note |
| Odd angles and distances | 10% | tilted up to ~45°, 10-60 cm away |

Vary in every group:

- **Lighting**: daylight by a window, indoor tube light, warm bulb, dim room, phone flash,
  direct sun with glare, shadow of a hand across the note.
- **Backgrounds**: wood, cloth (sarong, bed sheet), patterned tablecloth, newspaper, a wallet,
  a shop counter, other notes, skin of different tones.
- **Orientation**: upright, upside down, sideways.
- **Blur**: include a few slightly blurred photos (blind users can't check focus); drop
  photos where even you can't tell the value.

Also take **~100 "no note" photos** (receipts, bus tickets, lottery tickets, paper, wallets,
foreign notes). Put them in the dataset with no boxes: they teach the model not to call every
piece of paper a note. (Pascal VOC: an XML with no `<object>`; COCO: an image with no
annotations.)

Don't photograph anything private (ID cards, bank cards, faces) in the background.

## 3. Labelling

Draw one tight box around each visible note (the whole visible paper, not just the number)
and give it the class name. Either tool works:

- **Label Studio** (free, runs locally: `pip install label-studio` then `label-studio`).
  Project template *Computer Vision > Object Detection with Bounding Boxes*, labels
  `20 50 100 500 1000 5000`. Export as **Pascal VOC XML** or **COCO**.
- **Roboflow** (web). Create an *Object Detection* project, upload, label, then
  *Export > Pascal VOC* or *COCO JSON*. Turn **off** Roboflow's resize/augmentation when
  exporting; Model Maker does its own.

Rules: box every note you can identify, even partly hidden ones; skip a note only if you
truly can't tell its value. Keep labels exactly `20`, `50`, ... (no "Rs", no spaces).

## 4. Split into train / validation / test

Split **by photo session, not by single photo**: if you took 10 shots of the same note on the
same table, keep all 10 in one split, or the test score will be falsely high.

- `train/` - 80%
- `validation/` - 10% (used during training to pick the best weights)
- `test/` - 10% (never touched until the end; the honest score)

Make sure every class and every situation (folded, hand, several notes, dim light) appears in
all three. Folder layout:

```
lkr_dataset/
  train/        images/*.jpg   Annotations/*.xml      <- Pascal VOC
  validation/   images/*.jpg   Annotations/*.xml
  test/         images/*.jpg   Annotations/*.xml
```

or, for COCO, `images/*.jpg` plus `labels.json` in each split.

## 5. Train (Colab)

1. Open `train_lkr_notes.ipynb` in Colab (File > Upload notebook).
2. Runtime > Change runtime type > **T4 GPU**.
3. Upload `train_lkr_notes.py` and `lkr_dataset.zip`, run all cells.

Or directly:

```
!pip install -q mediapipe-model-maker
!python train_lkr_notes.py --data /content/lkr_dataset --format voc
```

Useful options: `--model multi_avg_384` (more accurate, slower), `--epochs 80`,
`--qat-epochs 0` (skip quantization-aware training; exports float16, about twice the size).

## 6. Reading the evaluation

The script prints COCO metrics on the **test** split (or validation if there is no test),
first for the float model and then for the int8 model:

- **AP50** - average precision when a box counts as right if it overlaps the true note by
  at least 50%. This is the number that matters most for speaking a value. Aim for
  **>= 0.90**; below 0.80 the app will often stay silent or name the wrong note.
- **AP** - the stricter average over 50-95% overlap. Lower than AP50 is normal (0.6-0.8 is fine).
- **ARmax10** - share of notes found at all. Low recall = the app misses notes.
- **Per-class AP** (when printed) - look for one weak value. Typical fixes: more photos of
  that note, especially the side or series it gets wrong; check those labels for mistakes.
- The **int8** numbers should be within ~0.02 of the float ones. A bigger drop: add
  `--qat-epochs 25`.

Then test for real: install the app, point it at each note in good and bad light, folded,
and in a hand. Wrong values spoken with "This looks like" (high confidence) are the worst
errors; collect photos of exactly those cases and retrain.

## 7. Put the model in the app

1. Rename the downloaded file to `lkr_notes.tflite` if needed.
2. Copy it to `app/src/main/assets/lkr_notes.tflite`.
3. Rebuild (`./gradlew :app:assemblePhoneDebug`). The build already keeps `.tflite`
   files uncompressed, which MediaPipe needs.

The labels are stored inside the model, so there is no separate labels file. The app accepts
any label whose first number is 20, 50, 100, 500, 1000 or 5000 and ignores anything else
(including Model Maker's `background` class).

Confidence wording in the app: a score of 0.70 or more is spoken as "This looks like ...",
0.45-0.70 as "This might be ...", and anything lower is ignored. The constants are in
`NoteAnnouncer.java` if your model needs different thresholds.
