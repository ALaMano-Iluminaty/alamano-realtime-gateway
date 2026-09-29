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
