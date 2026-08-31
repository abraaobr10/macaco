<img width="1672" height="1672" alt="Open Recorder 01" src="https://github.com/user-attachments/assets/36bf82a8-c5ea-4f26-bccf-03bebbc67969" /># Gravador AOSP

Aplicativo Android instalável inspirado no gravador de tela do AOSP/SystemUI. O projeto usa apenas APIs públicas da plataforma e não depende de bibliotecas externas em tempo de execução.

## Recursos

- gravação da tela inteira;
- seleção de um único aplicativo pelo seletor do sistema no Android 14 ou superior;
- áudio interno, microfone, ambos ou nenhum;
- mistura de áudio interno e microfone em AAC;
- contagem regressiva de três segundos;
- serviço em primeiro plano com ação para parar;
- salvamento MP4 em `Filmes/Screen recordings`;
- notificação para abrir ou compartilhar a gravação;
- opção experimental para mostrar toques, restaurada ao terminar.

## Compatibilidade

- `minSdk 29` — Android 10, mínimo necessário para `AudioPlaybackCapture`;
- `compileSdk 36` e `targetSdk 36` — Android 16;
- Java 17;
- Android Gradle Plugin 8.9.2 e Gradle 8.11.1.

Tela:
![<?xml version="1.0" standalone="no"?>
<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 20010904//EN"
 "http://www.w3.org/TR/2001/REC-SVG-20010904/DTD/svg10.dtd">
<svg version="1.0" xmlns="http://www.w3.org/2000/svg"
 width="1254.000000pt" height="1254.000000pt" viewBox="0 0 1254.000000 1254.000000"
 preserveAspectRatio="xMidYMid meet">
<rect width="1254" height="1254" fill="#ffffff"/>
<metadata>
Created by potrace 1.16, written by Peter Selinger 2001-2019
</metadata>
<g transform="translate(0.000000,1254.000000) scale(0.100000,-0.100000)"
fill="#000000" stroke="none">
<path d="M2276 9150 c-49 -11 -103 -24 -120 -31 -323 -127 -541 -371 -634
-707 l-27 -97 -3 -2150 c-2 -1566 0 -2175 8 -2244 29 -237 129 -440 297 -602
114 -110 212 -173 352 -226 149 -56 -21 -53 3030 -54 3038 0 2860 -3 3011 49
312 108 558 375 639 695 37 145 42 275 33 979 l-2 127 -127 98 c-71 54 -231
178 -356 276 -202 157 -233 177 -266 177 -20 0 -42 -5 -49 -12 -9 -9 -12 -176
-12 -687 0 -464 -4 -689 -11 -716 -15 -52 -82 -119 -134 -134 -29 -8 -791 -11
-2735 -11 -2585 0 -2697 1 -2735 19 -51 23 -101 79 -114 128 -8 26 -11 684
-11 2074 0 2262 -5 2106 66 2169 20 17 47 36 61 41 29 11 5448 14 5478 3 43
-15 88 -59 111 -108 l24 -51 0 -684 c0 -487 3 -688 11 -698 6 -7 30 -13 53
-13 34 0 52 9 107 53 36 28 148 117 249 197 101 80 229 181 284 225 l101 80 7
75 c4 41 6 260 3 485 -3 358 -6 421 -23 500 -44 202 -136 369 -286 517 -124
121 -261 199 -451 255 -57 17 -209 18 -2900 20 -2748 2 -2843 1 -2929 -17z
M10678 8277 c-53 -15 -77 -30 -168 -102 -36 -28 -164 -130 -285 -226 -121 -96
-280 -222 -353 -279 -74 -58 -243 -190 -377 -295 -218 -171 -593 -466 -1100
-866 -265 -209 -295 -236 -323 -297 -45 -99 -27 -228 44 -311 20 -23 71 -68
113 -101 42 -32 180 -139 306 -237 127 -98 286 -221 354 -273 67 -52 189 -147
270 -210 81 -63 235 -183 342 -267 107 -83 363 -284 569 -445 619 -484 571
-453 690 -446 91 5 151 41 194 118 l31 55 3 1985 c2 2180 6 2045 -58 2122 -56
68 -162 99 -252 75z"/>
</g>
</svg>
Uploading Open Recorder 01.svg…]()


O áudio interno só pode incluir aplicativos que autorizam a captura de reprodução. Conteúdo protegido por DRM, aplicativos bancários e janelas com `FLAG_SECURE` podem produzir áudio silencioso ou vídeo preto.

## Diferenças inevitáveis em relação ao SystemUI

O código original do AOSP é privilegiado e faz parte do sistema operacional. Em um APK comum:

1. O Android mostra uma confirmação antes de cada sessão de `MediaProjection`.
2. A seleção de aplicativo específico é fornecida pelo próprio Android 14+; não há seletor privado do SystemUI.
3. A seleção direta de displays externos do código mais recente do SystemUI não está disponível pela API pública equivalente.
4. “Mostrar toques” usa a autorização especial “Modificar configurações do sistema” e pode ser bloqueado por alguns fabricantes.
5. O app não pode capturar conteúdo que outro aplicativo marcou como seguro ou não capturável.

## Compilar

1. Abra esta pasta no Android Studio Meerkat 2024.3.1 ou mais recente.
2. Instale Android SDK Platform 36 e Build-Tools 36 pelo SDK Manager.
3. Aguarde a sincronização do Gradle.
4. Execute a configuração `app` em um aparelho Android 10 ou superior.

Pela linha de comando:

```bash
./gradlew assembleDebug
```

O APK será criado em `app/build/outputs/apk/debug/app-debug.apk`.

## Funcionamento

O fluxo segue a arquitetura do código AOSP fornecido:

1. `MainActivity` coleta as opções e solicita a autorização de captura.
2. `RecordingService` inicia como serviço em primeiro plano.
3. `ScreenRecorder` conecta `MediaProjection` a uma `VirtualDisplay` e à superfície do `MediaRecorder`.
4. `InternalAudioRecorder` usa `AudioPlaybackCapture` e, quando solicitado, mistura o microfone em PCM antes de codificar AAC.
5. `Mp4Muxer` combina a faixa H.264 e a faixa AAC.
6. O resultado é gravado no `MediaStore` e disponibilizado para abrir ou compartilhar.

## Licença

Apache License 2.0. Consulte `LICENSE` e `NOTICE`.
# macaco
# macaco
