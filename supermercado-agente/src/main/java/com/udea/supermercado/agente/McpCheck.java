package com.udea.supermercado.agente;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.mcp.McpToolset;
import java.util.*;
import java.util.stream.Collectors;

/** Verifica el cliente ADK contra el servidor real sin llamar a Gemini ni modificar pedidos. */
public final class McpCheck {
    public static void main(String[] args) throws Exception {
        McpToolset toolset = McpConnection.conectar();
        try {
            List<BaseTool> tools = toolset.getTools(null).toList().blockingGet();
            Set<String> nombres = tools.stream().map(BaseTool::name).collect(Collectors.toSet());
            Set<String> esperados = Set.of("listarProductos", "buscarProductos", "crearCarrito", "establecerCantidad",
                "consultarPedido", "configurarEntrega", "confirmarPedido", "cancelarPedido");
            if (!nombres.equals(esperados)) throw new IllegalStateException("Herramientas inesperadas: " + nombres);
            BaseTool buscar = tools.stream().filter(t -> t.name().equals("buscarProductos")).findFirst().orElseThrow();
            Map<String, Object> result = buscar.runAsync(Map.of("texto", "arroz"), null).blockingGet();
            ObjectMapper mapper = new ObjectMapper();
            var json = mapper.valueToTree(result);
            if (json.has("error")) throw new IllegalStateException("Fallo MCP: " + json);
            var payload = json.path("text_output").get(0);
            if (!payload.path("exito").asBoolean()) throw new IllegalStateException("Fallo de negocio: " + payload);
            System.out.println("ADK descubrio las 8 herramientas y ejecuto buscarProductos correctamente.");
            System.out.println(payload.toPrettyString());
        } finally {
            try { toolset.close(); }
            finally {
                // Este ejecutable termina aqui; libera los schedulers globales de sus clientes.
                reactor.core.scheduler.Schedulers.shutdownNow();
                io.reactivex.rxjava3.schedulers.Schedulers.shutdown();
            }
        }
    }
}
