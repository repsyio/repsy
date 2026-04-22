<a href="https://repsy.io" target="_blank"><img src="./repsy-frontend/src/assets/images/repsy.png" alt="Repsy Logo" width="200"/></a>
# Repsy: The Open Source Universal Package Repository

**Repsy** is an open-source, universal package repository that makes it easy to host, manage, and distribute your packages across multiple ecosystems — all in one place. With support for popular formats including Golang, Cargo (Rust), Docker, Maven, NPM, PyPI, and more, Repsy helps streamline your development workflows and supports teams of any size.
## Table of Contents

- [Features](#features)
- [Quick Start](#quick-start)
- [Installation](#installation)
    - [Using Docker (H2 - Embedded)](#option-1-docker-with-h2-embedded-database)
    - [Using Docker (PostgreSQL)](#option-2-docker-with-postgresql)
    - [Using Docker Compose (PostgreSQL)](#option-3-docker-compose-with-postgresql)
    - [Manual Installation](#manual-installation)
- [Configuration](#configuration)
- [Usage](#usage)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [License](#license)

## Features

- **Repository Management**: Create, manage, and organize repositories
- **Multi-Protocol Support**: Golang, Cargo (Rust), Maven, npm, PyPI, Docker registries
- **User Authentication**: Secure JWT-based authentication
- **Deploy Tokens**: Secure token-based deployment mechanism
- **Real-time Dashboard**: Monitor repository activity
- **RESTful API**: Comprehensive REST API
- **Database Support**: H2 (embedded) and PostgreSQL
- **Docker Ready**: Complete containerization support

## Quick Start

The fastest way to get Repsy up and running (uses embedded H2 database — no external dependencies):

```bash
docker run -d \
  --name repsy \
  -p 8080:8080 \
  -p 9090:9090 \
  repo.repsy.io/repsy/os/repsy:26.04.0
```

Access the application:
- **Backend API & Frontend (Web UI)**: http://localhost:8080
- **Repository Operations**: http://localhost:9090

**Default Admin Credentials:**
- Username: `admin`
- Password: since `ADMIN_INITIAL_PASSWORD` is not set, a random password is generated on first startup. Retrieve it from the logs:
  ```bash
  docker logs repsy | grep "password"
  ```

> **Note:** The H2 database is stored at `/app/data` inside the container. File storage defaults to `~/.repsy` on the host and is **not** covered by the volume mount. Set `STORAGE_BASE_PATH` to persist artifacts inside the same volume (see [Option 1](#option-1-docker-with-h2-embedded-database)).

## Installation

### Option 1: Docker with H2 (Embedded Database)

No external database required. Suitable for evaluation and development.

```bash
docker run -d \
  --name repsy \
  -p 8080:8080 \
  -p 9090:9090 \
  -e ADMIN_INITIAL_PASSWORD=YourSecurePassword123 \
  -v repsy-data:/app/data \
  repo.repsy.io/repsy/os/repsy:26.04.0
```

> The `-v repsy-data:/app/data` flag persists the H2 database across container restarts. Setting `STORAGE_BASE_PATH=/app/data/storage` ensures artifact file storage is also kept inside the same volume. Without it, artifacts default to `~/.repsy` on the host and are **not** covered by the volume mount.

### Option 2: Docker with PostgreSQL

```bash
# 1. Create a shared network
docker network create repsy-network

# 2. Start PostgreSQL
docker run -d \
  --name repsy-postgres \
  --network repsy-network \
  -e POSTGRES_DB=repsy \
  -e POSTGRES_USER=repsy \
  -e POSTGRES_PASSWORD=repsy123 \
  -p 5432:5432 \
  postgres:18

# 3. Start Repsy
docker run -d \
  --name repsy \
  --network repsy-network \
  -p 8080:8080 \
  -p 9090:9090 \
  -e DB_URL=jdbc:postgresql://repsy-postgres:5432/repsy \
  -e DB_USERNAME=repsy \
  -e DB_PASSWORD=repsy123 \
  -e ADMIN_INITIAL_PASSWORD=YourSecurePassword123 \
  repo.repsy.io/repsy/os/repsy:26.04.0
```

### Option 3: Docker Compose with PostgreSQL

```yaml
services:
  postgres:
    container_name: repsy-postgres
    hostname: repsy-postgres
    image: postgres:18
    environment:
      - POSTGRES_DB=repsy
      - POSTGRES_USER=repsy
      - POSTGRES_PASSWORD=repsy123
    ports:
      - "5432:5432"
    networks:
      - repsy-network

  repsy:
    container_name: repsy
    image: repo.repsy.io/repsy/os/repsy:26.04.0
    depends_on:
      - postgres
    ports:
      - "8080:8080"
      - "9090:9090"
    environment:
      - DB_URL=jdbc:postgresql://repsy-postgres:5432/repsy
      - DB_USERNAME=repsy
      - DB_PASSWORD=repsy123
      - ADMIN_INITIAL_PASSWORD=YourSecurePassword123
    networks:
      - repsy-network

networks:
  repsy-network:
    driver: bridge
```

### Manual Installation

**Prerequisites:**
- **Java**: JDK 25
- **Spring Boot**: 4.0.5
- **PostgreSQL**: 18
- **Angular**: 21
- **Maven**: 3.9.7 or higher
- **Node.js** 24.x (>=24.0.0 <25.0.0)

```bash
# 1. Build the backend
mvn clean install -DskipTests

# 2. Install frontend dependencies
cd repsy-frontend
pnpm install
cd ..

# 3. Run with embedded H2 database
cd repsy-backend
mvn spring-boot:run
```

Access at:
- **Frontend (Web UI)**: http://localhost:4200
- **Backend API**: http://localhost:8080
- **Repository Operations**: http://localhost:9090

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ADMIN_INITIAL_PASSWORD` | Initial admin password. Only applied on first startup when no admin exists. | *(empty)* |
| `DB_URL` | JDBC database URL. Defaults to embedded H2. | `jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` |
| `DB_USERNAME` | Database username | `repsy` |
| `DB_PASSWORD` | Database password | `repsy123` |
| `STORAGE_BASE_PATH` | Base directory for artifact file storage. Set to a path inside `/app/data` (e.g. `/app/data/storage`) to persist artifacts with a single volume mount. | `~/.repsy` |
| `JWT_SECRET` | JWT signing secret. If not set, a random 256-bit secret is generated at startup — all sessions are lost on restart. Set a stable value for production. | *(random)* |
| `SERVER_PORT` | Repository operations port | `9090` |
| `API_PORT` | Backend API and Frontend web UI port | `8080` |
| `H2_TCP_SERVER_ENABLED` | Enable H2 TCP server for external database access (development only) | `false` |
| `H2_TCP_SERVER_PORT` | H2 TCP server port | `9092` |

**Important Notes:**

- **Admin Username**: `admin`
- **Admin Initial Password**: Only applied when no admin user exists in the database. After first run, change your password through the application interface.
- **JWT_SECRET**: Set a stable value via environment variable to avoid session invalidation on restart.

## Usage

### First Login

1. Navigate to http://localhost:8080
2. Login with:
    - **Username**: `admin`
    - **Password**: the value you set for `ADMIN_INITIAL_PASSWORD`

For detailed information on creating repositories, managing deploy tokens, and using different protocols (Golang, Cargo(Rust), Maven, npm, PyPI, Docker), see the [documentation](https://docs.repsy.io).

## Troubleshooting

### Common Issues

**Port already in use:**
```bash
# Check what's using port 8080 or 9090
lsof -i :8080
lsof -i :9090
```

**Database connection failed:**
```bash
# Check if PostgreSQL is running
docker ps | grep postgres

# Check PostgreSQL logs
docker logs repsy-postgres
```

**Admin user not created:**
```bash
# Check application logs
docker logs repsy

# Verify ADMIN_INITIAL_PASSWORD was set
docker exec repsy env | grep ADMIN
```

**Can't login:**
- Verify `ADMIN_INITIAL_PASSWORD` was set before the first startup
- **Forgot admin password?** Reset it by setting hash to NULL in the database:
  ```sql
  -- Connect to PostgreSQL
  docker exec -it repsy-postgres psql -U repsy -d repsy

  -- Reset admin password hash
  UPDATE users SET hash = NULL, salt = NULL WHERE role = 'ADMIN';

  -- Exit and restart the application
  \q
  docker restart repsy

  -- Check logs for the new random password
  docker logs repsy | grep "Admin password"
  ```
  The application will automatically generate a new random password on next startup.

### Logs

```bash
docker logs -f repsy
docker logs -f repsy-postgres
```

### Reset Everything

```bash
# Stop and remove container
docker rm -f repsy

# Remove named volume (if used)
docker volume rm repsy-data

# Remove file storage
rm -rf ~/.repsy

# Start fresh (H2 example)
docker run -d \
  --name repsy \
  -p 8080:8080 \
  -p 9090:9090 \
  -e ADMIN_INITIAL_PASSWORD=YourSecurePassword123 \
  -e STORAGE_BASE_PATH=/app/data/storage \
  -v repsy-data:/app/data \
  repo.repsy.io/repsy/os/repsy:26.04.0
```

## Development

This section is for developers who want to contribute to or modify Repsy.

### Prerequisites

- **Java**: JDK 25
- **Spring Boot**: 4.0.5
- **PostgreSQL**: 18
- **Angular**: 21
- **Maven**: 3.9.7 or higher
- **Node.js** 24.x (>=24.0.0 <25.0.0)

### Project Structure

```
repsy/
├── repsy-backend/          # Spring Boot backend
│   ├── src/main/java/         # Java source code
│   ├── src/main/resources/    # Configuration files
│   └── src/test/              # Unit tests
├── repsy-frontend/         # Angular frontend
│   ├── src/app/               # Angular components
│   └── src/assets/            # Static assets
├── libs/                      # Shared libraries
│   ├── protocol-router/       # Protocol routing
│   ├── multiport/             # Multi-port handling
│   └── storage/               # Storage layer
└── repsy-protocols/           # Protocol implementations
```

### Development Setup

```bash
# 1. Start PostgreSQL for development
docker run -d \
  --name repsy-postgres \
  -e POSTGRES_DB=repsy \
  -e POSTGRES_USER=repsy \
  -e POSTGRES_PASSWORD=repsy_123 \
  -p 5432:5432 \
  postgres:18

# 2. Build backend
mvn clean install -DskipTests

# 3. Run backend in development mode
cd repsy-backend
mvn spring-boot:run

# 4. In another terminal, run frontend
cd repsy-frontend
pnpm install
pnpm start
```

Access development environment:
- **Frontend (Web UI)**: http://localhost:4200 (with hot reload)
- **Backend API**: http://localhost:8080
- **Repository Operations**: http://localhost:9090
- To inspect the H2 database directly, enable the TCP server with `H2_TCP_SERVER_ENABLED=true` and connect via `jdbc:h2:tcp://localhost:9092/~/repsy` using a tool like DBeaver or IntelliJ


### Building for Production

```bash
# Build Docker image (run from repo root)
docker build -f Dockerfile -t repsy:latest .
```

### Code Style

- **Java**: Follow Google Java Style Guide
- **TypeScript/Angular**: Follow Angular Style Guide
- **Commits**: Use conventional commits format

### Database Migrations

Migrations are managed with Flyway in `src/main/resources/db/migration/`.

```bash
# File format: V{version}__{description}.sql
# Example: V0002__add_user_roles.sql
```

### Contributing

1. Fork the repository
2. Create feature branch: `git checkout -b feature/amazing-feature`
3. Commit changes: `git commit -m 'feat: add amazing feature'`
4. Push to branch: `git push origin feature/amazing-feature`
5. Open Pull Request

**Contribution Guidelines:**
- Write tests for new features
- Update documentation
- Follow existing code style
- Keep commits atomic and well-described

## License

This project is licensed under the Apache License, Version 2.0 - see the [LICENSE.txt](LICENSE.txt) file for details.

## Support

- **Documentation**: [docs.repsy.io](https://docs.repsy.io)
- **Issues**: [GitHub Issues](https://github.com/repsyio/repsy/issues)

---

Developed using Spring Boot and Angular
