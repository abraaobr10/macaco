# Gravador AOSP

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
