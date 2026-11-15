package com.udea.supermercado.agente;

import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import java.time.Duration;

public final class McpConnection {
    private McpConnection() {}
    public static String variable(String nombre, String porDefecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? porDefecto : valor.strip();
    }
    public static McpToolset conectar() {
        return new McpToolset(StreamableHttpServerParameters.builder()
            .url(variable("MCP_SERVER_URL", "http://localhost:8089/mcp"))
            .timeout(Duration.ofSeconds(30)).build());
    }
}
