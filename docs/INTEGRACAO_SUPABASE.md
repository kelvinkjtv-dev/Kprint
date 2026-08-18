# Integração do KPrint com o Supabase

O KPrint não lê diretamente a tabela de pedidos. O backend coloca um **snapshot pronto para impressão** em `kprint_jobs`. Isso desacopla o aplicativo do formato interno do sistema de delivery, permite retentativas e evita que duas impressoras assumam o mesmo trabalho.

## 1. Aplicar a migration

Com a CLI do Supabase:

```bash
supabase db push
```

Ou copie o conteúdo de `supabase/migrations/20260817000000_kprint_queue.sql` no SQL Editor.

A migration:

- cria `kprint_printers` e `kprint_jobs`;
- ativa RLS sem expor leitura/escrita direta ao aplicativo;
- cria RPCs atômicas para reivindicar e confirmar impressões;
- devolve à fila um trabalho travado por mais de 5 minutos;
- limita cada trabalho a 5 tentativas por padrão.

## 2. Cadastrar o aparelho

Gere os valores fora do app. O token abaixo é apenas um exemplo; use um valor aleatório longo em produção.

```sql
with device as (
  select
    gen_random_uuid() as store_id,
    gen_random_uuid() as device_id,
    encode(gen_random_bytes(32), 'hex') as device_token
)
insert into public.kprint_printers (id, store_id, name, token_hash)
select
  device_id,
  store_id,
  'Balcão principal',
  encode(sha256(convert_to(device_token, 'UTF8')), 'hex')
from device
returning id as device_id, store_id;
```

O SQL Editor não retorna o CTE `device_token` no `returning`. Para uma instalação manual simples, defina e guarde um token explicitamente:

```sql
insert into public.kprint_printers (id, store_id, name, token_hash)
values (
  'UUID-DO-DISPOSITIVO',
  'UUID-DA-LOJA',
  'Balcão principal',
  encode(sha256(convert_to('TOKEN-ALEATORIO-COM-PELO-MENOS-32-CARACTERES', 'UTF8')), 'hex')
);
```

Preencha no app:

1. URL do projeto Supabase;
2. chave **anon/publishable** (nunca `service_role`);
3. `store_id`;
4. `device_id`;
5. token em texto puro usado para produzir `token_hash`.

O token é criptografado pelo Android Keystore antes de ser salvo.

## 3. Enfileirar um pedido

No backend, depois de confirmar o pedido, chame a função com a credencial `service_role` mantida **somente no servidor**:

```http
POST https://SEU-PROJETO.supabase.co/rest/v1/rpc/kprint_enqueue_job
apikey: SUA_SERVICE_ROLE
Authorization: Bearer SUA_SERVICE_ROLE
Content-Type: application/json

{
  "p_store_id": "UUID-DA-LOJA",
  "p_order_id": "pedido-1842",
  "p_payload": {
    "order_number": "1842",
    "merchant_name": "Minha Hamburgueria",
    "created_at": "17/08/2026 20:35",
    "customer": {
      "name": "Maria Silva",
      "phone": "(85) 99999-9999"
    },
    "delivery": {
      "type": "delivery",
      "address": "Rua Exemplo, 120, Centro, Fortaleza/CE"
    },
    "items": [
      {
        "quantity": 2,
        "name": "X-Burguer",
        "notes": "Sem cebola",
        "unit_price": 18.5,
        "total": 37
      }
    ],
    "totals": {
      "subtotal": 37,
      "delivery_fee": 5,
      "discount": 0,
      "total": 42
    },
    "payment": {
      "method": "Dinheiro",
      "change_for": 50
    },
    "notes": "Tocar a campainha"
  }
}
```

Também é possível chamar `supabase.rpc('kprint_enqueue_job', payload)` no backend.

### Campos aceitos

| Campo | Tipo | Observação |
|---|---|---|
| `order_number` | texto | Número visível no cupom |
| `merchant_name` | texto | Nome no topo |
| `created_at` | texto | Já formatado no fuso da loja |
| `customer` | objeto | `name`, `phone` |
| `delivery` | objeto | `type` (`delivery`, `pickup` ou `retirada`) e `address` |
| `items` | lista | `quantity`, `name`, `notes`, `unit_price`, `total` |
| `totals` | objeto | `subtotal`, `delivery_fee`, `discount`, `total` |
| `payment` | objeto | `method`, `change_for` opcional |
| `notes` | texto | Observação geral |

Valores monetários são números em reais, e não strings como `"R$ 10,00"`.

## 4. Como o segundo plano funciona

O usuário inicia o monitor uma vez. O Android mantém um **Foreground Service** com notificação permanente e o app consulta as RPCs a cada 3–60 segundos (5 por padrão). Trocar para WhatsApp, apagar a tela ou fechar a tela do KPrint não encerra o monitor nem o socket Bluetooth usado na impressão.

O aplicativo só abre a conexão RFCOMM no momento de imprimir e a fecha depois. Isso evita o problema do navegador perder o acesso Web Bluetooth quando sai de foco.

Após uma impressão física, o ID fica em um ledger local antes da confirmação remota. Se a internet cair nesse exato momento, a próxima tentativa confirma o trabalho sem imprimir uma segunda via.

> Nenhum sistema consegue garantir matematicamente “exactly once” com uma impressora física sem confirmação de hardware. O desenho reduz duplicações e registra falhas, mas desligar o aparelho exatamente entre a impressão e o registro local ainda pode gerar uma segunda via.

## 5. Integração com a tabela atual

Quando o schema real de pedidos estiver disponível, há duas opções:

- chamar `kprint_enqueue_job` no backend que já cria o pedido (recomendado);
- criar um trigger PostgreSQL que converta a linha de pedido para o payload acima.

Prefira a chamada explícita: um pedido normalmente só deve imprimir após pagamento/confirmação, não em todo `INSERT`.
