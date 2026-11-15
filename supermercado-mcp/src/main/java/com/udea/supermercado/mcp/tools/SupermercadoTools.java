package com.udea.supermercado.mcp.tools;

import com.udea.supermercado.mcp.dto.Vistas.*;
import com.udea.supermercado.mcp.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.Supplier;

@Component
public class SupermercadoTools {
    private static final Logger log = LoggerFactory.getLogger(SupermercadoTools.class);
    private final SupermercadoService servicio;
    public SupermercadoTools(SupermercadoService servicio) { this.servicio = servicio; }

    @Tool(description = "Lista productos del supermercado, precios en COP y existencias reales, incluyendo agotados.")
    public Respuesta<List<ProductoVista>> listarProductos() { return ejecutar(servicio::listarProductos); }

    @Tool(description = "Busca productos por fragmento del nombre, sin distinguir mayusculas. Lista vacia significa sin coincidencias.")
    public Respuesta<List<ProductoVista>> buscarProductos(@ToolParam(description = "Texto no vacio del nombre del producto") String texto) {
        return ejecutar(() -> servicio.buscarProductos(texto));
    }

    @Tool(description = "Crea un carrito persistente en estado BORRADOR. Guarda su id para las siguientes operaciones; no reserves existencias aun.")
    public Respuesta<PedidoVista> crearCarrito() { return ejecutar(servicio::crearCarrito); }

    @Tool(description = "Establece la cantidad TOTAL deseada de un producto en el borrador, no la incrementa. Cero lo retira. Repetir los mismos argumentos no duplica unidades.")
    public Respuesta<PedidoVista> establecerCantidad(
        @ToolParam(description = "Identificador del carrito/pedido devuelto por crearCarrito") Long pedidoId,
        @ToolParam(description = "Identificador real del producto del catalogo") Long productoId,
        @ToolParam(description = "Cantidad total entera entre 0 y 10000; cero elimina la linea") Integer cantidad) {
        return ejecutar(() -> servicio.establecerCantidad(pedidoId, productoId, cantidad));
    }

    @Tool(description = "Consulta productos, total en COP, domicilio gratuito, destinatario y estado BORRADOR, CONFIRMADO o CANCELADO de un pedido.")
    public Respuesta<PedidoVista> consultarPedido(@ToolParam(description = "Identificador del pedido") Long pedidoId) {
        return ejecutar(() -> servicio.consultarPedido(pedidoId));
    }

    @Tool(description = "Guarda los datos de entrega en un borrador. Solicita al usuario los datos faltantes, no los inventes.")
    public Respuesta<PedidoVista> configurarEntrega(
        @ToolParam(description = "Identificador del pedido") Long pedidoId,
        @ToolParam(description = "Nombre del destinatario, hasta 120 caracteres") String nombre,
        @ToolParam(description = "Telefono del destinatario, al menos 7 digitos") String telefono,
        @ToolParam(description = "Direccion completa de entrega, incluyendo ciudad, hasta 300 caracteres") String direccion) {
        return ejecutar(() -> servicio.configurarEntrega(pedidoId, nombre, telefono, direccion));
    }

    @Tool(description = "Confirma un pedido y descuenta existencias en una transaccion. Usar SOLO despues de mostrar el resumen y recibir confirmacion explicita del usuario. Repetir el mismo id no duplica el descuento.")
    public Respuesta<PedidoVista> confirmarPedido(@ToolParam(description = "Id del pedido que el usuario confirmo") Long pedidoId) {
        return ejecutar(() -> servicio.confirmarPedido(pedidoId));
    }

    @Tool(description = "Cancela un pedido y devuelve existencias si estaba confirmado. Pide confirmacion explicita del id al usuario antes de invocar. Repetir el mismo id no duplica la reposicion.")
    public Respuesta<PedidoVista> cancelarPedido(@ToolParam(description = "Id del pedido cuya cancelacion confirmo el usuario") Long pedidoId) {
        return ejecutar(() -> servicio.cancelarPedido(pedidoId));
    }

    private <T> Respuesta<T> ejecutar(Supplier<T> accion) {
        try { return Respuesta.ok(accion.get()); }
        catch (NegocioException e) { return Respuesta.error(e.getCodigo(), e.getMessage()); }
        catch (ConcurrencyFailureException e) {
            return Respuesta.error("CONFLICTO", "Otra operacion modifico los datos. Consulta el pedido y vuelve a intentarlo.");
        }
        catch (DataAccessException e) {
            log.error("Fallo de persistencia en herramienta MCP: {}", e.getClass().getSimpleName());
            return Respuesta.error("ERROR_PERSISTENCIA", "No fue posible completar la operacion. Consulta el estado antes de reintentar.");
        }
    }
}
