package com.udea.supermercado.mcp;

import com.udea.supermercado.mcp.model.EstadoPedido;
import com.udea.supermercado.mcp.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupermercadoIntegrationTests {
    @Autowired SupermercadoService servicio;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @BeforeEach
    void datos() {
        jdbc.execute("TRUNCATE detalle_pedido, pedido, producto RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO producto(nombre,categoria,precio,existencias) VALUES ('Arroz 1 kg','Despensa',4500,10),('Leche 1 litro','Lacteos',4200,10)");
    }

    @Test void catalogoYBusqueda() {
        assertThat(servicio.listarProductos()).hasSize(2);
        assertThat(servicio.buscarProductos("ARROZ")).singleElement().satisfies(p -> {
            assertThat(p.nombre()).isEqualTo("Arroz 1 kg");
            assertThat(p.moneda()).isEqualTo("COP");
        });
        assertThat(servicio.buscarProductos("inexistente")).isEmpty();
    }

    @Test void carritoCalculaTotalSinDuplicarCantidadesYRetiraProductos() {
        long id = servicio.crearCarrito().id();
        servicio.establecerCantidad(id, 1L, 2);
        servicio.establecerCantidad(id, 1L, 2);
        var pedido = servicio.establecerCantidad(id, 2L, 3);
        assertThat(pedido.total()).isEqualByComparingTo("21600.00");
        assertThat(pedido.productos()).hasSize(2);
        assertThat(stock(1)).isEqualTo(10);
        assertThat(servicio.establecerCantidad(id, 1L, 0).total()).isEqualByComparingTo("12600");
        assertThat(servicio.establecerCantidad(id, 1L, 0).productos()).hasSize(1);
    }

    @Test void conservaPrecioCotizadoAunqueCambieCatalogo() {
        long id = preparado(1, 2);
        jdbc.update("UPDATE producto SET precio=9999,version=version+1 WHERE id=1");
        assertThat(servicio.confirmarPedido(id).total()).isEqualByComparingTo("9000");
    }

    @Test void confirmaYCancelaUnaSolaVez() {
        long id = preparado(1, 2);
        assertThat(servicio.confirmarPedido(id).estado()).isEqualTo(EstadoPedido.CONFIRMADO);
        servicio.confirmarPedido(id);
        assertThat(stock(1)).isEqualTo(8);
        assertThat(servicio.cancelarPedido(id).estado()).isEqualTo(EstadoPedido.CANCELADO);
        servicio.cancelarPedido(id);
        assertThat(stock(1)).isEqualTo(10);
        assertThat(servicio.consultarPedido(id).direccion()).contains("Medellin");
    }

    @Test void cancelarBorradorNoReponeInventario() {
        long id = preparado(1, 2);
        servicio.cancelarPedido(id);
        assertThat(stock(1)).isEqualTo(10);
    }

    @Test void validaEntradasEIdentificadores() {
        long id = servicio.crearCarrito().id();
        falla("ENTRADA_INVALIDA", () -> servicio.establecerCantidad(id, 1L, -1));
        falla("ENTRADA_INVALIDA", () -> servicio.establecerCantidad(id, 1L, null));
        falla("ENTRADA_INVALIDA", () -> servicio.establecerCantidad(id, 1L, 10001));
        falla("ENTRADA_INVALIDA", () -> servicio.consultarPedido(null));
        falla("ENTRADA_INVALIDA", () -> servicio.buscarProductos(" "));
        falla("NO_ENCONTRADO", () -> servicio.consultarPedido(999L));
        falla("NO_ENCONTRADO", () -> servicio.establecerCantidad(id, 999L, 1));
        falla("SIN_EXISTENCIAS", () -> servicio.establecerCantidad(id, 1L, 11));
        falla("ENTRADA_INVALIDA", () -> servicio.configurarEntrega(id, " ", "3001234567", "Calle 1"));
        falla("ENTRADA_INVALIDA", () -> servicio.configurarEntrega(id, "Ana", "abcdefg", "Calle 1"));
        falla("ENTRADA_INVALIDA", () -> servicio.configurarEntrega(id, "Ana", "3001234567", ""));
    }

    @Test void noConfirmaSinProductosODireccion() {
        long id = servicio.crearCarrito().id();
        falla("CARRITO_VACIO", () -> servicio.confirmarPedido(id));
        servicio.establecerCantidad(id, 1L, 1);
        falla("ENTREGA_INCOMPLETA", () -> servicio.confirmarPedido(id));
        assertThat(stock(1)).isEqualTo(10);
    }

    @Test void faltaDeExistenciasRevierteTodosLosDescuentos() {
        long id = preparado(1, 2);
        servicio.establecerCantidad(id, 2L, 3);
        jdbc.update("UPDATE producto SET existencias=1,version=version+1 WHERE id=2");
        falla("SIN_EXISTENCIAS", () -> servicio.confirmarPedido(id));
        assertThat(stock(1)).isEqualTo(10);
        assertThat(stock(2)).isEqualTo(1);
        assertThat(servicio.consultarPedido(id).estado()).isEqualTo(EstadoPedido.BORRADOR);
    }

    @Test void soloSeModificanBorradores() {
        long id = preparado(1, 2);
        servicio.confirmarPedido(id);
        falla("ESTADO_INVALIDO", () -> servicio.establecerCantidad(id, 1L, 3));
        falla("ESTADO_INVALIDO", () -> servicio.configurarEntrega(id, "Ana", "3001234567", "Otra direccion"));
        servicio.cancelarPedido(id);
        falla("ESTADO_INVALIDO", () -> servicio.confirmarPedido(id));
    }

    @Test void dosComprasConcurrentesNoVendenLaMismaUltimaUnidad() throws Exception {
        jdbc.update("UPDATE producto SET existencias=1 WHERE id=1");
        long primero = preparado(1, 1), segundo = preparado(1, 1);
        List<String> resultados = confirmarEnParalelo(primero, segundo);
        assertThat(resultados).containsExactlyInAnyOrder("OK", "SIN_EXISTENCIAS");
        assertThat(stock(1)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedido WHERE estado='CONFIRMADO'", Integer.class)).isEqualTo(1);
    }

    @Test void dosConfirmacionesSimultaneasDelMismoPedidoDescuentanUnaVez() throws Exception {
        long id = preparado(1, 2);
        assertThat(confirmarEnParalelo(id, id)).containsExactly("OK", "OK");
        assertThat(stock(1)).isEqualTo(8);
    }

    @Test void mcpNegociaDescubreOchoHerramientasYCreaPedidoReal() throws Exception {
        var cliente = HttpClient.newHttpClient();
        var mapper = new ObjectMapper();
        var init = rpc(cliente, mapper, null, null, Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
            "params", Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of(),
                "clientInfo", Map.of("name", "integration-test", "version", "1"))));
        assertThat(init.statusCode()).isEqualTo(200);
        String session = init.headers().firstValue("Mcp-Session-Id").orElse(null);
        String protocol = json(mapper, init.body()).path("result").path("protocolVersion").asText();
        assertThat(protocol).isNotBlank();
        rpc(cliente, mapper, session, protocol, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        var listado = rpc(cliente, mapper, session, protocol, Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
        JsonNode tools = json(mapper, listado.body()).path("result").path("tools");
        assertThat(tools.size()).isEqualTo(8);
        List<String> nombres = new ArrayList<>();
        tools.forEach(t -> nombres.add(t.path("name").asText()));
        assertThat(nombres).containsExactlyInAnyOrder("listarProductos", "buscarProductos", "crearCarrito", "establecerCantidad",
            "consultarPedido", "configurarEntrega", "confirmarPedido", "cancelarPedido");
        var crear = rpc(cliente, mapper, session, protocol, llamada(3, "crearCarrito", Map.of()));
        var respuesta = resultadoHerramienta(mapper, crear);
        assertThat(respuesta.path("exito").asBoolean()).isTrue();
        long id = respuesta.path("datos").path("id").asLong();
        assertThat(servicio.consultarPedido(id).estado()).isEqualTo(EstadoPedido.BORRADOR);
        var invalida = rpc(cliente, mapper, session, protocol, llamada(4, "establecerCantidad",
            Map.of("pedidoId", id, "productoId", 1, "cantidad", -1)));
        var error = resultadoHerramienta(mapper, invalida);
        assertThat(error.path("exito").asBoolean()).isFalse();
        assertThat(error.path("codigo").asText()).isEqualTo("ENTRADA_INVALIDA");
    }

    private List<String> confirmarEnParalelo(long a, long b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrera = new CyclicBarrier(2);
        try {
            List<Future<String>> futuros = new ArrayList<>();
            for (long id : new long[]{a, b}) futuros.add(pool.submit(() -> {
                barrera.await(10, TimeUnit.SECONDS);
                try { servicio.confirmarPedido(id); return "OK"; }
                catch (NegocioException e) { return e.getCodigo(); }
            }));
            return List.of(futuros.get(0).get(20, TimeUnit.SECONDS), futuros.get(1).get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private long preparado(long productoId, int cantidad) {
        long id = servicio.crearCarrito().id();
        servicio.establecerCantidad(id, productoId, cantidad);
        servicio.configurarEntrega(id, "Ana Prueba", "3001234567", "Calle 1 # 2-3, Medellin");
        return id;
    }
    private int stock(long id) { return jdbc.queryForObject("SELECT existencias FROM producto WHERE id=?", Integer.class, id); }
    private void falla(String codigo, Runnable accion) {
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(NegocioException.class, e -> assertThat(e.getCodigo()).isEqualTo(codigo));
    }
    private Map<String, Object> llamada(int id, String nombre, Map<String, Object> argumentos) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call", "params", Map.of("name", nombre, "arguments", argumentos));
    }
    private HttpResponse<String> rpc(HttpClient cliente, ObjectMapper mapper, String session, String protocolo, Object cuerpo) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream");
        if (session != null) builder.header("Mcp-Session-Id", session);
        if (protocolo != null) builder.header("MCP-Protocol-Version", protocolo);
        return cliente.send(builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(cuerpo))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(ObjectMapper mapper, String cuerpo) {
        String json = cuerpo.strip().startsWith("{") ? cuerpo : cuerpo.lines()
            .filter(l -> l.startsWith("data:")).map(l -> l.substring(5).strip()).findFirst().orElseThrow();
        return mapper.readTree(json);
    }
    private JsonNode resultadoHerramienta(ObjectMapper mapper, HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode result = json(mapper, response.body()).path("result");
        assertThat(result.path("isError").asBoolean()).isFalse();
        return mapper.readTree(result.path("content").get(0).path("text").asText());
    }
}
