package com.udea.supermercado.mcp.service;

public class NegocioException extends RuntimeException {
    private final String codigo;
    public NegocioException(String codigo, String mensaje) { super(mensaje); this.codigo = codigo; }
    public String getCodigo() { return codigo; }
}
