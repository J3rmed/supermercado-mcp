package com.udea.supermercado.mcp.repository;

import com.udea.supermercado.mcp.model.Producto;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface ProductoRepository extends JpaRepository<Producto, Long> {
    List<Producto> findAllByOrderByIdAsc();
    List<Producto> findByNombreContainingIgnoreCaseOrderByIdAsc(String texto);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Producto p where p.id = :id")
    Optional<Producto> bloquear(@Param("id") Long id);
}
