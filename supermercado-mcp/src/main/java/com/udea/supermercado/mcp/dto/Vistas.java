package com.udea.supermercado.mcp.dto;

import com.udea.supermercado.mcp.model.EstadoPedido;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Vistas {
    private Vistas() {}
    public record ProductoVista(Long id, String nombre, String categoria, BigDecimal precio,
                                int existencias, String moneda) {}
    public record LineaVista(Long productoId, String nombre, int cantidad,
                             BigDecimal precioUnitario, BigDecimal subtotal) {}
    public record PedidoVista(Long id, EstadoPedido estado, String nombre, String telefono,
                              String direccion, Instant creadoEn, List<LineaVista> productos,
                              BigDecimal total, BigDecimal costoDomicilio, String moneda) {}
    public record Respuesta<T>(boolean exito, String codigo, String mensaje, T datos) {
        public static <T> Respuesta<T> ok(T datos) { return new Respuesta<>(true, "OK", "Operacion realizada", datos); }
        public static <T> Respuesta<T> error(String codigo, String mensaje) { return new Respuesta<>(false, codigo, mensaje, null); }
    }
}
