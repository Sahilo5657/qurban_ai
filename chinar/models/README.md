# Vosk Models

This directory holds speech-to-text model files.

## Recommended Model

For the discovered hardware (i7-7500U, 16 GB RAM, no dedicated GPU), use a small Vosk model:

**vosk-model-small-en-us-0.15** (~40 MB)

### Download

1. Visit: https://alphacephei.com/vosk/models
2. Download `vosk-model-small-en-us-0.15.zip`
3. Extract here so the path is: `models/stt/vosk-model-small-en-us-0.15/`

The directory should contain files like `am/final.mdl`, `conf/`, `graph/`, etc.

### License

Vosk models are licensed under Apache 2.0.
See: https://github.com/alphacep/vosk-api/blob/master/LICENSE

## Alternative Models

| Model | Size | Accuracy | Use Case |
|---|---|---|---|
| vosk-model-small-en-us-0.15 | ~40 MB | Good | Low-resource systems |
| vosk-model-en-us-0.22 | ~1.8 GB | Better | Systems with ≥4 GB free RAM |
| vosk-model-en-us-0.22-lgraph | ~128 MB | Good | Balance of size and accuracy |

## Other Languages

Vosk supports 20+ languages. Replace the model directory and update
`config/config.toml` → `[stt] model_path` and `language` accordingly.
