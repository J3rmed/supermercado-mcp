# Supermercado a domicilio con MCP

## 1. Proyecto inicial y requisitos

| Componente | Version / configuracion |
|---|---|
| Java | 17 |
| Spring Boot del servidor | 4.1.2 |
| Spring AI | 2.0.1, gestionada por su BOM |
| Google ADK y ADK Dev | 1.14.0 en ambos artefactos |
| PostgreSQL | Imagen `postgres:17-alpine` |
| Maven | Wrapper incluido en cada proyecto |
| Transporte MCP | Streamable HTTP, servidor sincrono |

`supermercado-mcp` procede de Spring Initializr: Maven, Java, Jar, grupo `com.udea`, paquete `com.udea.supermercado.mcp`. Dependencias seleccionadas: Spring Web, Spring Data JPA, PostgreSQL Driver, Validation, Model Context Protocol Server, Flyway Migration y Testcontainers. Se cambio `<java.version>` a `17`.

`supermercado-agente` es un proyecto Maven independiente. ADK Dev aporta su servidor web y sus versiones transitivas; no se le impone el parent Spring Boot del servidor MCP.

Verifica Java y Docker Desktop (contenedores Linux):

```powershell
java -version
docker version
.\supermercado-mcp\mvnw.cmd -version
```

No necesitas instalar Maven ni PostgreSQL en Windows. Node.js solo es necesario para MCP Inspector.

## 2. Configurar el entorno

Desde la raiz `D:\Dev\mcp_lab`, crea `.env` si todavia no existe:

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
```

Edita `.env` y sustituye `CAMBIAR_CLAVE_LOCAL` por una contrasena local; agrega tu clave en `GOOGLE_API_KEY` para conversar con Gemini. Si ya tienes `.env` y una base creada, conserva su contrasena: cambiar el archivo no cambia las credenciales de una base existente. `.env` esta excluido de Git.

Docker Compose lee `.env` automaticamente desde la raiz. Para ejecutar el servidor o el agente, importa las variables en **cada nueva terminal PowerShell**, antes de cambiar a la carpeta del proyecto:

```powershell
cd D:\Dev\mcp_lab
Get-Content -LiteralPath .env | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $nombre = $Matches[1]
        $valor = $Matches[2].Trim()
        [Environment]::SetEnvironmentVariable($nombre, $valor, 'Process')
    }
}
```

Utiliza el formato simple `VARIABLE=valor`, sin comillas ni comentarios al final de los valores. Este bloque no ejecuta el contenido del archivo ni muestra las credenciales. Spring Boot y el agente no leen `.env` automaticamente; tambien puedes definir las variables en la configuracion de ejecucion de tu IDE.

| Variable | Valor inicial / uso |
|---|---|
| `DB_PORT` | `55433`, puerto publicado por Docker |
| `DB_URL` | `jdbc:postgresql://localhost:55433/supermercado` |
| `DB_USERNAME` | `supermercado` |
| `DB_PASSWORD` | Contrasena local configurada en `.env` |
| `MCP_PORT` | `8089` |
| `MCP_SERVER_URL` | `http://localhost:8089/mcp` |
| `ADK_PORT` | `8081` |
| `GEMINI_MODEL` | `gemini-flash-lite-latest` |
| `GOOGLE_API_KEY` | Tu clave de Gemini; necesaria solo para conversar |

Se eligieron estos puertos porque 5432, 55432, 8088 y 8080 ya estaban ocupados por otros proyectos. Si cambias `DB_PORT`, actualiza tambien `DB_URL`; si cambias `MCP_PORT`, actualiza `MCP_SERVER_URL`.

## 3. Iniciar PostgreSQL

```powershell
docker compose up -d --wait
docker compose ps
```

Compose crea exclusivamente el proyecto `mcp-lab-supermercado`, la base `supermercado` y su volumen persistente. Publica la base en la interfaz local. Flyway crea las tablas al arrancar Spring y carga doce productos ficticios, incluido un producto agotado.

El esquema usa identidades PostgreSQL, restricciones de cantidades y precios, claves foraneas y unicidad de producto dentro de un pedido. Se utiliza `ddl-auto=validate`; Hibernate no modifica el esquema. Las migraciones ya aplicadas no deben editarse: agrega una nueva migracion para cambios posteriores.

## 4. Iniciar el servidor MCP

En una terminal que se mantendra abierta, importa las variables como se indica en el paso 2 y ejecuta:

```powershell
cd D:\Dev\mcp_lab\supermercado-mcp
.\mvnw.cmd -B -ntp spring-boot:run
```

El servidor atiende en `http://localhost:8089/mcp`. Esa direccion es un endpoint de protocolo, no una pagina HTML.

Organizacion del servidor: `model` contiene entidades; `repository`, acceso JPA; `service`, reglas y transacciones; `dto`, resultados serializables; `tools`, herramientas; `config`, su registro.

### Datos y reglas

`Producto` contiene nombre, categoria, precio y existencias. `Pedido` contiene estado, destinatario, telefono, direccion, fecha y total. `DetallePedido` relaciona productos y pedidos con cantidad y precio unitario.

El carrito es un pedido `BORRADOR`. Solo los borradores admiten cambios. Al confirmar, pasa a `CONFIRMADO`; cancelar un borrador o un confirmado lo lleva a `CANCELADO`. No se simulan estados de reparto.

- Moneda COP; cantidades por unidad; domicilio gratuito; sin pagos reales.
- El precio unitario se captura desde el catalogo al agregar la linea y se conserva, incluso si cambia posteriormente el catalogo. Los totales se calculan con `BigDecimal` en el servidor.
- Un borrador no reserva existencias. Se comprueban al agregar productos y nuevamente al confirmar.
- Confirmar exige productos, destinatario, telefono valido y direccion completa proporcionada por el usuario.
- La confirmacion y los descuentos se realizan en una sola transaccion. Una falta de existencias revierte todos los cambios.
- Los bloqueos de fila serializan cambios sobre el mismo pedido. Los productos se bloquean en orden de id para evitar vender la ultima unidad dos veces y reducir interbloqueos; las entidades tambien tienen `@Version`.
- Confirmar o cancelar repetidamente el mismo pedido no duplica descuentos ni reposiciones.
- `establecerCantidad` fija el total de unidades; repetirla no incrementa la cantidad. Cero elimina la linea.

### Contrato de herramientas

| Herramienta | Argumentos |
|---|---|
| `listarProductos` | Ninguno |
| `buscarProductos` | `texto` |
| `crearCarrito` | Ninguno |
| `establecerCantidad` | `pedidoId`, `productoId`, `cantidad` |
| `consultarPedido` | `pedidoId` |
| `configurarEntrega` | `pedidoId`, `nombre`, `telefono`, `direccion` |
| `confirmarPedido` | `pedidoId` |
| `cancelarPedido` | `pedidoId` |

Todas devuelven `{ "exito": true/false, "codigo": "...", "mensaje": "...", "datos": ... }`. Las reglas fallidas usan codigos como `ENTRADA_INVALIDA`, `NO_ENCONTRADO`, `SIN_EXISTENCIAS`, `CARRITO_VACIO`, `ENTREGA_INCOMPLETA` y `ESTADO_INVALIDO`. El consumidor debe revisar `exito`, aunque la llamada de protocolo se haya completado correctamente. Argumentos que no cumplen el esquema MCP pueden producir un error del propio protocolo.

## 5. Probar el protocolo y las operaciones

Con el servidor MCP iniciado, abre otra terminal, importa las variables del paso 2 y comprueba la conexion desde el cliente real de ADK:

```powershell
cd D:\Dev\mcp_lab\supermercado-agente
.\mvnw.cmd -B -ntp compile exec:java@mcp-check
```

Esta comprobacion negocia la conexion, verifica las ocho herramientas e invoca `buscarProductos` con el texto `arroz`. No modifica pedidos ni necesita una clave de Gemini. Las pruebas automatizadas del servidor tambien verifican la negociacion y llamadas MCP reales por HTTP.

Para inspeccion visual:

```powershell
npx @modelcontextprotocol/inspector
```

Abre la URL que indique Inspector, selecciona **Streamable HTTP**, configura `http://localhost:8089/mcp` y conecta. Deben aparecer ocho herramientas. Node 24 del equipo cumple el requisito del Inspector consultado.

## 6. Iniciar el chatbot ADK

Con PostgreSQL y MCP funcionando, configura `GOOGLE_API_KEY` en `.env`. En otra terminal, importa las variables del paso 2 y ejecuta:

```powershell
cd D:\Dev\mcp_lab\supermercado-agente
.\mvnw.cmd -B -ntp compile exec:java@adk-web "-Dadk.port=$env:ADK_PORT"
```

No compartas la clave ni la incluyas en capturas. Abre `http://localhost:8081` y selecciona el agente **supermercado**. ADK web es una interfaz de desarrollo para la demostracion del laboratorio.

Se usa Flash-Lite porque el alias Flash devolvio alta demanda durante la verificacion. Puedes cambiar `GEMINI_MODEL` y reiniciar el agente. El cliente limita cada peticion al proveedor a 30 segundos sin reintentos HTTP automaticos; si el proveedor rechaza la peticion, revisa el error en ADK y vuelve a intentar cuando este disponible.

Conversacion sugerida:

1. `Que productos tienen? Busco arroz y leche.`
2. `Quiero dos unidades de arroz y una de leche.`
3. `El destinatario es Cliente Demo, telefono 3001234567 y direccion Calle de prueba 1, Medellin.`
4. `Muestrame el resumen antes de confirmar.`
5. Tras revisar cantidades, total y direccion: `Si, confirma ese pedido.`
6. `Consulta el estado del pedido.`
7. `Quiero cancelarlo.` Confirma el identificador cuando el agente lo solicite.

El agente debe pedir datos faltantes y confirmacion explicita antes de comprar o cancelar. El servidor sigue siendo la autoridad sobre precios, estados e inventario. La confirmacion conversacional es una instruccion al agente; no constituye autenticacion ni una barrera de autorizacion del servidor.

## 7. Verificar los datos y conservar evidencias

```powershell
docker compose exec postgres psql -U supermercado -d supermercado -c "SELECT id, nombre, precio, existencias FROM producto ORDER BY id;"
docker compose exec postgres psql -U supermercado -d supermercado -c "SELECT id, estado, nombre, direccion, total FROM pedido ORDER BY id;"
docker compose exec postgres psql -U supermercado -d supermercado -c "SELECT pedido_id, producto_id, cantidad, precio_unitario FROM detalle_pedido ORDER BY pedido_id, producto_id;"
```

Ejecuta las consultas desde `D:\Dev\mcp_lab`. Si personalizas `DB_USERNAME`, sustituye el usuario en los comandos anteriores. Guarda como evidencia la conversacion ADK y las consultas SQL del mismo pedido antes y despues de cada cambio. Usa solo datos ficticios.

Para una demostracion con Arroz 1 kg:

1. Consulta sus existencias iniciales.
2. Pide al chatbot crear un borrador con dos unidades y datos de entrega ficticios; anota el identificador devuelto.
3. Consulta el pedido y su detalle en SQL: deben reflejar dos unidades y el inventario inicial.
4. Solicita cambiar la cantidad total a tres unidades: el total debe recalcularse y el inventario debe permanecer igual.
5. Confirma explicitamente el pedido: su estado debe pasar a `CONFIRMADO` y el inventario debe disminuir en tres unidades.
6. Solicita cancelar el mismo pedido y confirma su identificador: debe quedar `CANCELADO` y restaurar las existencias iniciales.

Utiliza el identificador real en las consultas; por ejemplo, para el pedido 5:

```powershell
docker compose exec postgres psql -U supermercado -d supermercado -c "SELECT id, estado, total FROM pedido WHERE id=5; SELECT producto_id, cantidad, precio_unitario FROM detalle_pedido WHERE pedido_id=5; SELECT id, nombre, existencias FROM producto WHERE id=1;"
```

El servidor y la base se pueden reiniciar manteniendo los pedidos. Para detenerlos, usa Ctrl+C en las terminales Java y `docker compose stop` en la raiz. `docker compose down` conserva el volumen; **`down -v` lo elimina**, por lo que no es necesario para el uso normal.

## 8. Pruebas automatizadas

```powershell
# Servidor: pruebas con PostgreSQL efimero y empaquetado.
cd D:\Dev\mcp_lab\supermercado-mcp
.\mvnw.cmd -B -ntp verify

# Agente: compilacion y empaquetado.
cd D:\Dev\mcp_lab\supermercado-agente
.\mvnw.cmd -B -ntp verify
```

Las pruebas del servidor crean PostgreSQL efimero mediante Testcontainers, independiente del volumen de desarrollo. Cubren migraciones, busquedas, cantidades, precios conservados, totales, validaciones, transacciones, cancelacion, idempotencia, dos compras concurrentes por la ultima unidad y llamadas MCP reales por HTTP.

El agente no tiene una suite propia de pruebas automatizadas; su compilacion no usa Gemini. El objetivo `exec:java@mcp-check` comprueba su integracion con el servidor levantado y tampoco necesita clave. La conversacion con el modelo requiere una clave valida y disponibilidad del proveedor.

## Referencias

- [Spring Boot: requisitos de Java](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring AI: compatibilidad y BOM](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [Spring AI: servidor MCP Streamable HTTP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)
- [Google ADK Java](https://adk.dev/get-started/java/)
- [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector)
- [Imagen oficial de PostgreSQL](https://hub.docker.com/_/postgres)
