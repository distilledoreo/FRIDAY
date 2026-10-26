# Wake-phrase models

Used by `WakeWordDetector` to hear "Hey FRIDAY" on the phone.

- `melspectrogram.onnx` and `embedding_model.onnx`: the shared openWakeWord
  feature models, from the openWakeWord v0.5.1 release
  (https://github.com/dscripka/openWakeWord), Apache License 2.0. The
  embedding model derives from Google's speech_embedding model, Apache
  License 2.0.
- `hey_friday.onnx`: the community "hey_Friday!" openWakeWord model from
  https://github.com/fwartner/home-assistant-wakewords-collection
  (`en/hey_friday`), MIT License.

Checked on FRIDAY's own text-to-speech voice: it catches "Hey Friday" and
ignores ordinary speech, including sentences with "Friday" in them. A bare
"Friday" or "Happy Friday" sometimes passes, and "Hey Freddy" usually does.
A model trained with those as negatives would fix that.
