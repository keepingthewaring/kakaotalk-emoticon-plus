    .section .rodata
    .globl _emo_dex_start
    .globl _emo_dex_end
_emo_dex_start:
    .incbin "/root/KakaoTalkEmoPlus/build/payload.dex"
_emo_dex_end:
    .globl _emo_pine_start
    .globl _emo_pine_end
_emo_pine_start:
#if defined(__aarch64__)
    .incbin "/root/KakaoTalkEmoPlus/vendor/pine/libpine-arm64-v8a.so"
#elif defined(__arm__)
    .incbin "/root/KakaoTalkEmoPlus/vendor/pine/libpine-armeabi-v7a.so"
#else
#error "unsupported ABI"
#endif
_emo_pine_end:
