package com.udea.supermercado.mcp.service;

import com.udea.supermercado.mcp.dto.Vistas.*;
import com.udea.supermercado.mcp.model.*;
import com.udea.supermercado.mcp.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class SupermercadoService {
    private final ProductoRepository productos;
    private final PedidoRepository pedidos;

    public SupermercadoService(ProductoRepository productos, PedidoRepository pedidos) {
        this.productos = productos;
        this.pedidos = pedidos;
    }

    public List<ProductoVista> listarProductos() {
        return productos.findAllByOrderByIdAsc().stream().map(this::vista).toList();
    }

    public List<ProductoVista> buscarProductos(String texto) {
        return productos.findByNombreContainingIgnoreCaseOrderByIdAsc(texto(texto, "Busqueda", 120))
            .stream().map(this::vista).toList();
    }

    @Transactional
    public PedidoVista crearCarrito() { return vista(pedidos.save(new Pedido())); }

    @Transactional
    public PedidoVista establecerCantidad(Long pedidoId, Long productoId, Integer cantidad) {
        validarId(productoId);
        if (cantidad == null || cantidad < 0 || cantidad > 10000) {
            throw error("ENTRADA_INVALIDA", "La cantidad debe ser un entero entre 0 y 10000; cero retira el producto.");
        }
        Pedido pedido = bloquearPedido(pedidoId);
        exigirBorrador(pedido);
        Producto producto = productos.findById(productoId)
            .orElseThrow(() -> error("NO_ENCONTRADO", "No existe el producto " + productoId));
        DetallePedido linea = pedido.getDetalles().stream()
            .filter(d -> d.getProducto().getId().equals(productoId)).findFirst().orElse(null);
        if (cantidad == 0) {
            if (linea != null) pedido.getDetalles().remove(linea);
        } else {
            exigirExistencias(producto, cantidad);
            if (linea == null) pedido.getDetalles().add(new DetallePedido(pedido, producto, cantidad));
            else linea.cantidad(cantidad);
        }
        pedido.recalcular();
        pedidos.flush();
        return vista(pedido);
    }

    public PedidoVista consultarPedido(Long pedidoId) {
        validarId(pedidoId);
        return vista(pedidos.findById(pedidoId)
            .orElseThrow(() -> error("NO_ENCONTRADO", "No existe el pedido " + pedidoId)));
    }

    @Transactional
    public PedidoVista configurarEntrega(Long pedidoId, String nombre, String telefono, String direccion) {
        nombre = texto(nombre, "Nombre", 120);
        telefono = texto(telefono, "Telefono", 30);
        direccion = texto(direccion, "Direccion", 300);
        if (!telefono.matches("[+0-9() .-]{7,30}") || telefono.replaceAll("\\D", "").length() < 7) {
            throw error("ENTRADA_INVALIDA", "El telefono debe contener al menos 7 digitos y solo signos telefonicos habituales.");
        }
        Pedido pedido = bloquearPedido(pedidoId);
        exigirBorrador(pedido);
        pedido.entrega(nombre, telefono, direccion);
        return vista(pedido);
    }

    @Transactional
    public PedidoVista confirmarPedido(Long pedidoId) {
        Pedido pedido = bloquearPedido(pedidoId);
        // El bloqueo serializa confirmaciones repetidas del mismo pedido.
        if (pedido.getEstado() == EstadoPedido.CONFIRMADO) return vista(pedido);
        exigirBorrador(pedido);
        if (pedido.getDetalles().isEmpty()) throw error("CARRITO_VACIO", "Agrega productos antes de confirmar.");
        if (pedido.getNombre() == null || pedido.getTelefono() == null || pedido.getDireccion() == null) {
            throw error("ENTREGA_INCOMPLETA", "Configura nombre, telefono y direccion antes de confirmar.");
        }
        // Orden global de bloqueo para evitar interbloqueos entre pedidos con varios productos.
        for (DetallePedido detalle : ordenadas(pedido)) {
            Producto producto = productos.bloquear(detalle.getProducto().getId()).orElseThrow();
            exigirExistencias(producto, detalle.getCantidad());
            producto.descontar(detalle.getCantidad());
        }
        pedido.recalcular();
        pedido.cambiarEstado(EstadoPedido.CONFIRMADO);
        pedidos.flush();
        return vista(pedido);
    }

    @Transactional
    public PedidoVista cancelarPedido(Long pedidoId) {
        Pedido pedido = bloquearPedido(pedidoId);
        if (pedido.getEstado() == EstadoPedido.CANCELADO) return vista(pedido);
        if (pedido.getEstado() == EstadoPedido.CONFIRMADO) {
            for (DetallePedido detalle : ordenadas(pedido)) {
                productos.bloquear(detalle.getProducto().getId()).orElseThrow().reponer(detalle.getCantidad());
            }
        }
        pedido.cambiarEstado(EstadoPedido.CANCELADO);
        pedidos.flush();
        return vista(pedido);
    }

    private Pedido bloquearPedido(Long id) {
        validarId(id);
        return pedidos.bloquear(id).orElseThrow(() -> error("NO_ENCONTRADO", "No existe el pedido " + id));
    }
    private void exigirBorrador(Pedido pedido) {
        if (pedido.getEstado() != EstadoPedido.BORRADOR) {
            throw error("ESTADO_INVALIDO", "El pedido " + pedido.getId() + " esta " + pedido.getEstado() + "; solo se editan borradores.");
        }
    }
    private void exigirExistencias(Producto p, int cantidad) {
        if (cantidad > p.getExistencias()) {
            throw error("SIN_EXISTENCIAS", "Existencias insuficientes para " + p.getNombre() + ". Disponibles: " + p.getExistencias());
        }
    }
    private void validarId(Long id) {
        if (id == null || id <= 0) throw error("ENTRADA_INVALIDA", "El identificador debe ser un entero positivo.");
    }
    private String texto(String valor, String campo, int maximo) {
        if (valor == null || valor.isBlank() || valor.strip().length() > maximo) {
            throw error("ENTRADA_INVALIDA", campo + " es obligatorio y admite hasta " + maximo + " caracteres.");
        }
        return valor.strip();
    }
    private NegocioException error(String codigo, String mensaje) { return new NegocioException(codigo, mensaje); }
    private List<DetallePedido> ordenadas(Pedido pedido) {
        return pedido.getDetalles().stream().sorted(Comparator.comparing(d -> d.getProducto().getId())).toList();
    }
    private ProductoVista vista(Producto p) {
        return new ProductoVista(p.getId(), p.getNombre(), p.getCategoria(), p.getPrecio(), p.getExistencias(), "COP");
    }
    private PedidoVista vista(Pedido p) {
        List<LineaVista> lineas = ordenadas(p).stream().map(d -> new LineaVista(d.getProducto().getId(),
            d.getProducto().getNombre(), d.getCantidad(), d.getPrecioUnitario(), d.subtotal())).toList();
        return new PedidoVista(p.getId(), p.getEstado(), p.getNombre(), p.getTelefono(), p.getDireccion(),
            p.getCreadoEn(), lineas, p.getTotal(), new BigDecimal("0.00"), "COP");
    }
}
