# Chinar — Environment Discovery Report

**Generated**: 2026-10-25  
**Host**: `sahil-khan\pukht`

## Verified Observations

| Property | Value |
|---|---|
| Operating System | Microsoft Windows 11 Home |
| OS Build | 10.0.22631 |
| Architecture | x64 |
| Processor | Intel Core i7-7500U @ 2.70 GHz |
| Physical Cores | 2 |
| Logical Processors | 4 |
| Total RAM | 16,217 MB |
| Available RAM (at discovery) | ~5,000 MB |
| GPU | Intel HD Graphics 620 (integrated, 1 GB shared) |
| CUDA / Dedicated GPU | **Not available** |
| Storage (D:) | ~130 GB free of ~280 GB |
| Storage (C:) | ~49 GB free of ~194 GB |
| Battery | Present, 20% charge, discharging |
| Virtualization | Bare metal laptop |

## Software Environment

| Component | Status |
|---|---|
| Python | 3.13.5 (`C:\Users\pukht\AppData\Local\Programs\Python\Python313\python.exe`) |
| pip | 25.3 (reports Python 3.14 path — dual install possible) |
| Ollama | **Not installed** |
| systemd | **Not applicable** (Windows) |
| xdotool / ydotool | **Not applicable** (Windows) |
| PipeWire / PulseAudio / ALSA | **Not applicable** (Windows) |

## Audio Devices

| Device | Status |
|---|---|
| Speakers (Realtek High Definition Audio SST) | OK |
| Microphone Array (Realtek High Definition Audio SST) | OK |

## User Identity

- Username: `pukht`
- Machine: `sahil-khan`
- Administrator: **No** (standard user)

## Platform Decision

**Target platform: Windows 11 Desktop**

### Implications

- **Service management**: Windows Task Scheduler or `pythonw.exe` background process (no systemd)
- **Desktop automation**: `pyautogui` and Win32 API via `pywin32` / `ctypes`
- **Audio capture**: `sounddevice` or `pyaudio` (PortAudio backend)
- **Audio control**: `pycaw` (Windows Core Audio API) or PowerShell commands
- **Network control**: `netsh` commands via subprocess
- **Bluetooth**: PowerShell / Windows Settings (limited without admin)
- **STT engine**: Vosk (preferred — low resource, streaming, CPU-friendly for i7-7500U)
- **TTS engine**: `pyttsx3` (uses Windows SAPI5 — no downloads needed)
- **Local LLM**: Ollama when user installs it; deterministic rules always available
- **Bootstrap script**: PowerShell `.ps1` script (replaces `chinar-daemon.sh`)

### Constraints

1. No dedicated GPU — whisper.cpp would be slow; Vosk is the better choice
2. Standard user — cannot install system services without elevation
3. No Ollama — LLM features disabled until user installs it
4. Limited RAM headroom — must be careful with model sizes

## Assumptions

- The microphone array is functional and accessible from user-space audio APIs
- Python 3.13.5 is the primary runtime (despite pip reporting 3.14)
- The project will be stored on D: drive where space is ample
