package com.udea.supermercado.mcp.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Pedido {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private EstadoPedido estado = EstadoPedido.BORRADOR;
    @Column(length = 120) private String nombre;
    @Column(length = 30) private String telefono;
    @Column(length = 300) private String direccion;
    @Column(nullable = false) private Instant creadoEn = Instant.now();
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total = new BigDecimal("0.00");
    @Version private Long version;
    @OneToMany(mappedBy = "pedido", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DetallePedido> detalles = new ArrayList<>();

    public Pedido() {}
    public Long getId() { return id; }
    public EstadoPedido getEstado() { return estado; }
    public String getNombre() { return nombre; }
    public String getTelefono() { return telefono; }
    public String getDireccion() { return direccion; }
    public Instant getCreadoEn() { return creadoEn; }
    public BigDecimal getTotal() { return total; }
    public List<DetallePedido> getDetalles() { return detalles; }
    public void entrega(String nombre, String telefono, String direccion) {
        this.nombre = nombre;
        this.telefono = telefono;
        this.direccion = direccion;
    }
    public void cambiarEstado(EstadoPedido estado) { this.estado = estado; }
    public void recalcular() {
        total = detalles.stream().map(DetallePedido::subtotal)
            .reduce(new BigDecimal("0.00"), BigDecimal::add);
    }
}
