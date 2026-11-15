package com.udea.supermercado.mcp.model;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
public class Producto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 120)
    private String nombre;
    @Column(nullable = false, length = 80)
    private String categoria;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal precio;
    @Column(nullable = false)
    private int existencias;
    @Version private Long version;

    protected Producto() {}
    public Long getId() { return id; }
    public String getNombre() { return nombre; }
    public String getCategoria() { return categoria; }
    public BigDecimal getPrecio() { return precio; }
    public int getExistencias() { return existencias; }
    public void descontar(int cantidad) { existencias -= cantidad; }
    public void reponer(int cantidad) { existencias += cantidad; }
}
