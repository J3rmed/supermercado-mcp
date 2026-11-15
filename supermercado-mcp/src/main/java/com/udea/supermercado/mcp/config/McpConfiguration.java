package com.udea.supermercado.mcp.config;

import com.udea.supermercado.mcp.tools.SupermercadoTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpConfiguration {
    @Bean
    ToolCallbackProvider supermercadoToolCallbacks(SupermercadoTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
