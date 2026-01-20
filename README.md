# SessionCast Server

WebSocket relay server for streaming tmux terminal sessions to web browsers.

## Overview

SessionCast Server acts as a bridge between terminal agents and web clients, enabling real-time terminal session streaming with features like:

- Real-time screen streaming with gzip compression
- Multi-session management per machine
- OAuth2 authentication (Google)
- Owner-based session isolation
- Automatic stale session cleanup

## Architecture

```
┌─────────────┐     WebSocket      ┌──────────────────┐     WebSocket      ┌─────────────┐
│   Agent     │ ◄──────────────────►│  SessionCast    │◄──────────────────►│  Web Client │
│  (tmux)     │    screen/keys     │     Server       │    screen/keys     │  (Browser)  │
└─────────────┘                    └──────────────────┘                    └─────────────┘
```

## Requirements

- Java 17+
- Maven 3.6+

## Configuration

Create `application.yml` or set environment variables:

```yaml
server:
  port: 8080

spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            redirect-uri: ${OAUTH2_REDIRECT_URI}

app:
  frontend:
    url: ${FRONTEND_URL:http://localhost:3000}
  cors:
    allowed-origins: ${CORS_ORIGINS:http://localhost:3000}
  auth:
    allowed-domains: ${ALLOWED_DOMAINS:}
  jwt:
    secret: ${JWT_SECRET:your-secret-key}
    expiration-ms: 86400000
```

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `GOOGLE_CLIENT_ID` | Google OAuth2 client ID | Yes |
| `GOOGLE_CLIENT_SECRET` | Google OAuth2 client secret | Yes |
| `FRONTEND_URL` | Web client URL | Yes |
| `CORS_ORIGINS` | Allowed CORS origins | Yes |
| `OAUTH2_REDIRECT_URI` | OAuth2 callback URL | Yes |
| `ALLOWED_DOMAINS` | Comma-separated allowed email domains | No |
| `JWT_SECRET` | JWT signing secret | Yes |

## Build & Run

```bash
# Build
mvn clean package -DskipTests

# Run
java -Xmx384m -jar target/relay-server-1.0.0.jar
```

## WebSocket Protocol

### Message Types

**From Agent:**
```json
{"type": "register", "role": "host", "session": "machine/session", "meta": {"label": "Session Name", "machineId": "machine"}}
{"type": "screen", "session": "machine/session", "payload": "<base64-screen-data>"}
{"type": "screenGz", "session": "machine/session", "payload": "<base64-gzip-compressed-data>"}
```

**From Web Client:**
```json
{"type": "register", "role": "viewer", "session": "machine/session"}
{"type": "keys", "session": "machine/session", "payload": "keystroke-data"}
{"type": "resize", "session": "machine/session", "meta": {"cols": "120", "rows": "40"}}
{"type": "listSessions"}
{"type": "createSession", "meta": {"machineId": "machine", "sessionName": "new-session"}}
{"type": "killSession", "session": "machine/session"}
```

**From Server:**
```json
{"type": "screen", "session": "machine/session", "payload": "<base64-screen-data>"}
{"type": "screenGz", "session": "machine/session", "payload": "<base64-gzip-compressed-data>"}
{"type": "sessionList", "sessions": [...]}
{"type": "sessionStatus", "session": "machine/session", "status": "online|offline"}
```

## Deployment

### Systemd Service

```ini
[Unit]
Description=SessionCast Server
After=network.target

[Service]
Type=simple
User=appuser
WorkingDirectory=/opt/sessioncast
Environment="GOOGLE_CLIENT_ID=your-client-id"
Environment="GOOGLE_CLIENT_SECRET=your-secret"
Environment="FRONTEND_URL=https://your-domain.com"
Environment="CORS_ORIGINS=https://your-domain.com"
Environment="JWT_SECRET=your-jwt-secret"
ExecStart=/usr/bin/java -Xmx384m -jar server.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## Performance Tuning

The server is optimized for low-memory environments:

- WebSocket buffer: 256KB
- Idle timeout: 5 minutes
- Tomcat threads: max 50, min-spare 5
- Stale session cleanup: every 5 minutes (30 min threshold)
- Stats logging: every 1 minute

## Related Projects

- [sessioncast/web](https://github.com/sessioncast/web) - Web client
- [sessioncast/agent](https://github.com/sessioncast/agent) - Terminal agent

## License

MIT License
