# ADR-003 — Erros de logcat: tela branca do launcher (Moto E32)

## Contexto

Após instalar o APK do Kairos Root no Moto E32 (Android 11) e abrir o aplicativo, a tela fica branca. Foi capturado o logcat para identificar a causa.

## Erros encontrados no logcat

### Erro 1 (principal) — ClassNotFoundException: WaterfallManager

```
11-15 15:29:12.212  1687  1687 E ReflectUtils: Exception on invoke method
11-15 15:29:12.212  1687  1687 E ReflectUtils: java.lang.ClassNotFoundException: motorola.core_services.misc.WaterfallManager
11-15 15:29:12.212  1687  1687 E ReflectUtils: 	at java.lang.Class.classForName(Native Method)
11-15 15:29:12.212  1687  1687 E ReflectUtils: 	at java.lang.Class.forName(Class.java:454)
11-15 15:29:12.212  1687  1687 E ReflectUtils: 	at java.lang.Class.forName(Class.java:379)
11-15 15:29:12.212  1687  1687 E ReflectUtils: 	at com.android.launcher3.ReflectUtils.invokeMethod(ReflectUtils.java:206)
11-15 15:29:12.212  1687  1687 E ReflectUtils: 	at com.android.launcher3.Utilities.isWaterfallDisplay(Utilities.java:306)
11-15 15:29:12.212  1687  1687 E ReflectUtils: 	at com.android.launcher3.LauncherRootView.onApplyWindowInsets(LauncherRootView.java:68)
```

Este erro se repete múltiplas vezes (a cada travessia de janela/insets). Ocorre no `com.android.launcher3` do sistema Motorola, não no nosso código.

**Causa:** O Kairos Root é declarado como launcher padrão (categoria `HOME` no `AndroidManifest.xml`). Quando o sistema troca para o nosso launcher, o `LauncherRootView` do sistema tenta invocar `motorola.core_services.misc.WaterfallManager` (classe proprietária da Motorola para detecção de display com bordas "waterfall"). Essa classe não existe no classpath do dispositivo → `ClassNotFoundException` → exceção refletida durante `onApplyWindowInsets`.

**Impacto na tela branca:** A exceção ocorre durante o layout/insets da janela do launcher. Como o Kairos Root substitui o launcher do sistema, a falha na renderização de insets do `LauncherRootView` pode deixar a tela em branco (sem conteúdo desenhado, sem fallback).

### Erro 2 — Sem UICC (informativo, não crítico)

```
11-15 15:29:11.010  1282  1282 E PhoneInterfaceManager: [PhoneIntfMgr] getCarrierPackageNamesForIntentAndPhone: No UICC
```

Não há chip SIM. Não relacionado à tela branca.

### Erro 3 — BufferQueueProducer disconnect (informativo)

```
11-15 15:29:12.505   547  1543 E BufferQueueProducer: [com.motorola.launcher3/com.android.launcher3.CustomizationPanelLauncher#0] disconnect: not connected
```

Desconexão do launcher anterior (Motorola Launcher3) ao ser substituído. Não relacionado à tela branca.

## Erros NÃO encontrados (boa notícia)

- Sem `FATAL EXCEPTION`
- Sem `AndroidRuntime` crash da nossa app
- Sem `Resources$NotFoundException`
- Sem `ClassNotFoundException` de nossas classes
- Sem `NullPointerException` no `statusView`

O `KrootService` inicia normalmente:
```
I KrootService: Kairos Root service iniciado
I KrootService: Verificando se krootd ja esta online...
I KrootService: Testando se root esta disponível via su -c id...
```

## Hipótese da tela branca

O `ClassNotFoundException: motorola.core_services.misc.WaterfallManager` ocorre durante `LauncherRootView.onApplyWindowInsets`, que é chamado quando a janela do launcher é exibida. Como não há tratamento de exceção nesse caminho do sistema (Launcher3 do Motorola), a renderização da janela pode ser interrompida, resultando em tela branca.

## Possíveis correções

| Opção | Descrição | Prós | Contras |
|-------|-----------|------|---------|
| A | Remover `android:category.HOME` do `AndroidManifest.xml` | Elimina a interação com `LauncherRootView` do sistema | Não funciona como launcher padrão |
| B | Adicionar `android:windowSoftInputMode` e flags de janela diferentes | Pode evitar o caminho de `onApplyWindowInsets` problemático | Pode não resolver |
| C | Adicionar `try/catch` ao redor do conteúdo da activity | Não captura exceções do sistema (Launcher3) | Não resolve |
| D | Definir `android:resizeableActivity="false"` no manifest | Reduz chamadas de insets | Pode não resolver |
| E | Usar tema sem `Fullscreen` ou sem flags imersivas | Reduz complexidade de insets | Muda visual |

## Conclusão

A tela branca não é causada por erro no código Kotlin/Java do Kairos Root, mas por uma incompatibilidade entre o `Launcher3` do Motorola (que busca `motorola.core_services.misc.WaterfallManager`) e a substituição do launcher padrão pelo nosso app. A correção deve focar no `AndroidManifest.xml` (categoria HOME e flags de janela) ou em não substituir o launcher do sistema.

## Arquivos relacionados

- `app/src/main/AndroidManifest.xml` — declaração da activity como HOME
- `app/src/main/res/layout/activity_main.xml` — layout da activity principal
- `app/src/main/java/com/chronokairo/kroot/MainActivity.java` — flags de janela imersiva
