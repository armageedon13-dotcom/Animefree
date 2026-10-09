# Anifree Android — versão com Media3 e downloads autorizados

## Recursos
- Catálogo AniList (metadados, não hospedagem de episódios)
- Tema preto e amarelo, pesquisa e aba Comunidade (somente informativa)
- Reprodução híbrida: embeds oficiais existentes e player nativo Media3 para fontes explicitamente aprovadas
- Player Media3 com preferência por áudio japonês, legendas externas WebVTT pt-BR e teto de resolução 1080p (fallback automático)
- Downloads MP4 apenas se a fonte declarar `downloadAllowed=true`, com URL HTTPS MP4 validada e host aprovado
- DownloadManager para Wi-Fi e biblioteca local com status, abertura offline e exclusão

## Configurar fontes reais
Edite `LicensedMedia.kt` e substitua `LicensedCatalog.episodes = emptyList()` por episódios cujos provedores tenham **autorizado explicitamente** streaming interno e/ou download. Preencha `allowedHosts` com domínios exatos confiáveis e URLs oficiais. Não basta que o arquivo esteja acessível na internet para ter permissão de redistribuição ou download.

Para uso em produção, NÃO mantenha a lista hardcoded: implemente backend com moderação, verificação de licenças, assinatura de respostas e controle de expiração de links. O app não extrai URLs de serviços pagos, não burla DRM e não converte vídeos protegidos.

## Abrir
1. Abra a pasta `AnifreeAndroid` no Android Studio.
2. Instale Android SDK 35, JDK 17 e sincronize Gradle.
3. Execute em Android 8+ (API 26+).

## Limitações
- Sem provedores licenciados configurados, o player e os downloads não exibem episódios. Isso é intencional.
- 1080p, legenda pt-BR e MP4 são oferecidos apenas quando disponíveis na fonte autorizada.
- Links assinados com query string ou sem extensão `.mp4` exigem validação de formato via backend; o protótipo os rejeita.
- O APK não foi compilado neste ambiente. Valide build, fluxos de download e permissões em dispositivo real.
- Login por CPF, biometria e moderação backend ainda não foram implementados.


## Novos / Top 10 / perfis
- Abas Novos (AniList START_DATE_DESC) e Top 10 (AniList POPULARITY_DESC). **Não são visualizações reais**.
- Perfis Adulto e Kids (um de cada por instalação, sem sincronização de contas ainda). Troca Kids -> Adulto protegida por BiometricPrompt/credencial do aparelho.
- Kids: lista de IDs previamente aprovados com classificação brasileira até 12 anos, atualmente **vazia** por segurança. A API AniList não fornece classificação indicativa brasileira confiável. A revisão humana é obrigatória.
- A autenticação local protege apenas a interface; para contas reais e múltiplos aparelhos, implemente controle de acesso e verificação de idade também no servidor.
- As abas são uma implementação inicial e não um serviço de streaming licenciado.

## PIN parental (versão experimental)
- Na primeira troca de perfil, o responsável define um PIN numérico de 4 a 6 dígitos.
- O PIN é solicitado tanto para entrar no Kids quanto para sair dele.
- Ao reabrir o aplicativo com Kids selecionado, a área de conteúdo fica bloqueada até a confirmação do PIN.
- O PIN é armazenado como hash PBKDF2-HMAC-SHA256 com salt aleatório (não em texto puro).
- Após cinco erros consecutivos, ocorre bloqueio temporário de um minuto, somente no processo atual.
- Esta implementação é **local e inicial**. Não substitui autenticação parental em servidor, recuperação segura de PIN, controle de tentativas persistente, nem proteção contra limpeza dos dados do app ou reinstalação. Não está pronta para uso como controle parental robusto em produção.
