# ALaMano Realtime Gateway

Gateway delgado que consume eventos de RabbitMQ y los distribuye a clientes web mediante WebSocket/STOMP. No contiene reglas de negocio ni expone una API REST.

## Ejecutar localmente

Requiere Java 21, Maven Wrapper y RabbitMQ accesible. La llave incluida en `dev-keys/` es solo para desarrollo local: genera una llave propia para cualquier entorno compartido o productivo y configura `JWT_PUBLIC_KEY_LOCATION`.

```bash
./mvnw spring-boot:run
./scripts/token.sh
```

En Windows usa `mvnw.cmd`. Desde PowerShell hay que anteponer `.\`, porque PowerShell no ejecuta programas de la carpeta actual sin ruta:

```powershell
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

El endpoint WebSocket nativo es `ws://localhost:8083/ws` (sin SockJS). El cliente debe enviar `Authorization: Bearer <JWT>` como header STOMP del frame `CONNECT`. Los JWT deben estar firmados con RS256.

## Destinos STOMP

- `/topic/map`: eventos `professional.online` y `professional.disconnected`.
- `/topic/service.{id}`: destino previsto para eventos de servicio.
- `/user/queue/errors`: destino de usuario previsto para errores.

Suscripción y conexión de ejemplo usando `@stomp/stompjs`:

```javascript
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: 'ws://localhost:8083/ws',
  connectHeaders: { Authorization: `Bearer ${token}` },
  onConnect: () => client.subscribe('/topic/map', frame => console.log(JSON.parse(frame.body))),
});
client.activate();
```

Generar un token: `./scripts/token.sh [sub] [role] [minutos] [llave-privada]`. Por defecto usa `vendedor-1`, `PROFESSIONAL`, 60 minutos y `dev-keys/dev-private.pem`. El script requiere OpenSSL.

## Variables de entorno

| Variable | Valor predeterminado | Uso |
|---|---|---|
| `RABBITMQ_HOST` | `localhost` | Host de RabbitMQ |
| `RABBITMQ_PORT` | `5672` | Puerto de RabbitMQ |
| `RABBITMQ_USER` | `guest` | Usuario RabbitMQ |
| `RABBITMQ_PASSWORD` | `guest` | Contraseña RabbitMQ |
| `JWT_PUBLIC_KEY_LOCATION` | `classpath:keys/public.pem` | Ubicación de llave RSA pública PEM |
| `HOSTNAME` | `local` | Identificador de instancia y nombre de cola |
| `ALLOWED_ORIGINS` | `http://localhost,http://localhost:5173` | Orígenes permitidos separados por comas |

## HU5: presencia del vendedor

El Gateway registra las sesiones WebSocket autenticadas con rol `PROFESSIONAL` en la instancia actual. Al cerrar la pestaña o cerrarse una conexión por pérdida de red (detectada cuando vencen los heartbeats STOMP de 10 segundos), solo publica un aviso cuando termina la última sesión del vendedor en esa instancia. Si mantiene otra pestaña abierta en la misma instancia, su presencia continúa activa.

El evento técnico se publica en el exchange `alamano.events` con routing key y tipo `professional.connection.lost`:

```json
{
  "eventId": "uuid-nuevo",
  "type": "professional.connection.lost",
  "schemaVersion": 1,
  "occurredAt": "2026-10-07T15:30:00Z",
  "correlationId": "uuid-nuevo",
  "payload": {
    "professionalId": "pro-1",
    "gatewayInstance": "local"
  }
}
```

El Gateway solo informa la pérdida técnica. El Core decide si el vendedor debe quedar `OFFLINE` y, si corresponde, publica `professional.disconnected` para que el Gateway lo reenvíe al mapa.

**Limitación conocida:** cada instancia solo conoce sus propias sesiones. Si un vendedor mantiene conexiones simultáneas en instancias distintas, la desconexión en una puede producir un aviso aunque siga conectado a otra. Para el MVP se asume una sola instancia; como mejora futura, el registro de sesiones puede compartirse mediante Redis u otro almacenamiento común.
