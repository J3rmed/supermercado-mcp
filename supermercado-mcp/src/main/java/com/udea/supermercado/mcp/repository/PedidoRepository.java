package com.udea.supermercado.mcp.repository;

import com.udea.supermercado.mcp.model.Pedido;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PedidoRepository extends JpaRepository<Pedido, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pedido p where p.id = :id")
    Optional<Pedido> bloquear(@Param("id") Long id);
}
