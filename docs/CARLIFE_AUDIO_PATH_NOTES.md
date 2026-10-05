# CarLife Audio Path — Notes Only (Phase 9.2a)

NOT IMPLEMENTED in Phase 9.2a (video only). Recording what the audit of
`CarLife-Android-Vehicle-V2.0/carlife-sdk` showed, for Phase 9.2c.

## Channels (`Constants`)

| Channel | Hex | Role |
|---|---|---|
| `MSG_CHANNEL_AUDIO` | 3 | MEDIA (music) |
| `MSG_CHANNEL_AUDIO_TTS` | 4 | TTS / navigation guidance |
| `MSG_CHANNEL_AUDIO_VR` | 5 | VR / voice assistant uplink-downlink |

## Codecs (from open source)

- `vehicle-app/audio/AACDecoder.kt` — AAC decoding on the vehicle side.
- Feature `FEATURE_CONFIG_AAC_SUPPORT` is advertised in the V2 sample
  features map (AAC capable).
- Old (2017) implementation additionally used PCM paths (LPCM) — to be
  confirmed against the real protocol before Phase 9.2c mapping.

## Planned mapping (NOT implemented yet)

| CarLife role | DiPlay role (`ProjectionAudioChannel`) |
|---|---|
| MEDIA | MEDIA |
| TTS | NAVIGATION |
| VR | VOICE_ASSISTANT |

into the shared `AudioAccessUnit` pipeline (`AndroidMediaSink` /
AudioTrack) — same renderer as CarPlay/CarLink. Real semantics must be
confirmed from the V2 protocol messages before coding; nothing is hardcoded
now.

## Notes

- `AudioFocusManager`, `AudioPlayTask`/`AudioPlayer` in the sample app are
  upstream reference players — NOT to be adopted; DiPlay renders audio only
  through the shared media pipeline.
- Touch/keys (`CarLife.receiver().onTouchEvent/onKeyEvent`) likewise stay
  unimplemented in 9.2a (Phase 9.2b).
