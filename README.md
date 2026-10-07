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
- `/topic/service.{id}`: eventos `tracking.updated` del servicio (ubicación y ETA del vendedor).
- `/app/location`: destino de envío (SEND) de la ubicación del vendedor.
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

## HU6: ubicación y tracking

El vendedor envía su ubicación por STOMP a `/app/location` (no por HTTP):

```javascript
client.publish({
  destination: '/app/location',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ latitude: 4.7826, longitude: -74.0435 }),
});
```

El id del vendedor sale siempre del token del CONNECT, nunca del mensaje. El Gateway ignora la ubicación (sin responder error; eso es AB#341) si el usuario no es `PROFESSIONAL`, si las coordenadas son nulas o están fuera de rango (latitud −90..90, longitud −180..180), si el vendedor no tiene un servicio en curso o si llega antes del intervalo mínimo.

Flujo completo:

```
Vendedor ──SEND /app/location {latitude, longitude}──▶ Gateway
Gateway  ──location.updated──▶ RabbitMQ ──▶ Core (calcula el ETA)
Core     ──tracking.updated──▶ RabbitMQ ──▶ Gateway ──▶ /topic/service.{serviceId}
```

El cliente del servicio se suscribe a `/topic/service.{serviceId}` y recibe el sobre completo de `tracking.updated`.

| Evento | Publica | Payload |
|---|---|---|
| `service.status.changed` | Core | `serviceId`, `professionalId`, `clientId`, `previousStatus`, `status`, `version` |
| `location.updated` | Gateway | `professionalId`, `serviceId`, `latitude`, `longitude`, `recordedAt` |
| `tracking.updated` | Core | `serviceId`, `professionalId`, `latitude`, `longitude`, `etaSeconds`, `recordedAt` |

Todos usan el sobre común (`eventId`, `type`, `schemaVersion`, `occurredAt`, `correlationId`, `payload`). El Gateway no recibe su propio `location.updated` porque ningún binding de su cola coincide con esa routing key.

**Límite de frecuencia:** se acepta como máximo una ubicación por vendedor cada `alamano.gateway.location-min-interval-ms` (2000 ms por defecto); las demás se descartan.

**Bindings de RabbitMQ:** en un topic exchange `*` cubre una sola palabra, así que `service.*` no recibe `service.status.changed` (tres palabras) y por eso ese evento tiene su propio binding. Cada evento nuevo de varias palabras que el Gateway deba recibir necesita el suyo en `RabbitConfig`.

**Limitación conocida:** el registro de servicios activos (`ActiveServiceRegistry`) vive en memoria, es por instancia y se pierde al reiniciar. Se vuelve a llenar con el siguiente `service.status.changed` de cada servicio, así que, tras un reinicio, las ubicaciones de un servicio en curso se descartan hasta su próximo cambio de estado. Como mejora futura, puede compartirse mediante Redis.
