# KPrint

Aplicativo Android nativo para receber pedidos de uma fila segura no Supabase e imprimir em impressoras térmicas Bluetooth ESC/POS, inclusive quando o usuário abre o WhatsApp ou deixa o KPrint em segundo plano.

## Estado do projeto

Este repositório contém um MVP Android completo e a infraestrutura inicial do Supabase:

- serviço Android em primeiro plano, independente do navegador;
- fila com polling configurável (5 segundos por padrão);
- impressão Bluetooth Classic/RFCOMM para ESC/POS 58 e 80 mm;
- seleção entre aparelhos já pareados;
- impressão de teste e cupom formatado;
- retentativas, claim atômico e recuperação de trabalhos travados;
- proteção contra segunda via após falha de internet no ACK;
- histórico local de impressões e erros;
- retomada após reinicialização do Android;
- credencial própria do dispositivo criptografada com Android Keystore;
- RLS: o app não recebe `service_role` e não acessa as tabelas diretamente.

## Como funciona

```text
Sistema de delivery
        │
        │ kprint_enqueue_job (backend/service_role)
        ▼
Supabase: kprint_jobs
        │
        │ RPC segura / claim atômico
        ▼
Foreground Service do KPrint
        │
        │ Bluetooth Classic (ESC/POS)
        ▼
Impressora térmica
```

O Android exibe uma notificação permanente enquanto o monitor está ativo. Essa é a forma suportada pelo sistema operacional para continuar trabalhando em segundo plano sem depender do ciclo de vida do navegador.

## Requisitos

- Android 8.0 ou superior (API 26);
- impressora Bluetooth Classic compatível com ESC/POS/SPP;
- papel de 58 mm (padrão) ou 80 mm;
- projeto Supabase;
- JDK 17 e Android SDK 35 para compilar.

> Impressoras exclusivamente BLE podem exigir um driver específico. O MVP implementa Bluetooth Classic, usado pela maioria das térmicas genéricas de 58 mm.

## Primeira execução

1. Aplique [`supabase/migrations/20260817000000_kprint_queue.sql`](supabase/migrations/20260817000000_kprint_queue.sql) no projeto Supabase.
2. Cadastre uma impressora/dispositivo e guarde `store_id`, `device_id` e o token.
3. Enfileire o pedido pelo **backend** do delivery.
4. No Android, pareie a impressora nas configurações de Bluetooth.
5. Abra KPrint → **Configurar**, informe Supabase e selecione a impressora.
6. Salve e use **Imprimir teste**.
7. Toque em **Iniciar monitor**. Pode abrir o WhatsApp; a notificação “KPrint está ativo” deve permanecer.

O contrato do payload e exemplos estão em **[docs/INTEGRACAO_SUPABASE.md](docs/INTEGRACAO_SUPABASE.md)**.

## Compilar

No Android Studio, abra a raiz do repositório e execute o módulo `app`. Pela linha de comando:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

APK gerado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```


## Permissões e bateria

- Android 12+: aceite “Dispositivos próximos” (`BLUETOOTH_CONNECT`).
- Android 13+: aceite notificações para visualizar claramente o estado do monitor.
- Alguns fabricantes encerram serviços agressivamente. Se necessário, marque KPrint como **Sem restrições** em Bateria.
- Não use “Forçar parada”: o Android impede qualquer retomada automática até o usuário abrir o app novamente.

## Segurança

Não configure `service_role` no aplicativo. Ela deve existir somente no backend. O app usa a chave anon/publishable mais uma credencial individual do dispositivo, validada dentro de funções `security definer`. As tabelas ficam com RLS ativada e sem políticas de acesso direto para `anon`/`authenticated`.

## Próxima etapa da integração

Como o schema e a URL do sistema atual ainda não foram fornecidos, o app adota um contrato de pedido documentado. Para conectar à produção, adapte o backend que confirma o pedido para chamar `kprint_enqueue_job` — ou forneça o formato atual da tabela/API para criar esse adaptador.
