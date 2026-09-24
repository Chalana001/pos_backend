package com.chala.posapp.repository;

import com.chala.posapp.entity.supplier.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    List<Supplier> findByActiveTrue();

    List<Supplier> findByNameContainingIgnoreCase(String name);

    boolean existsByEmail(String email);

    /** Live rows only. Pairs with the native query below to tell a real clash from a tombstone. */
    boolean existsByPhone(String phone);

    /**
     * Does ANY row hold this phone number, tombstone included.
     *
     * The entity carries @SQLRestriction("deleted_at IS NULL"), so a derived query can
     * never see a soft-deleted supplier. That matters on create: the tombstone keeps its
     * phone number (deleting a supplier must not rewrite its history), and the unique key
     * still enforces it, so a derived check would report "free" and let the insert through
     * to fail at the constraint. A native query reads the table directly and tells the
     * truth, letting the service say why the number is taken instead of surfacing the
     * generic "invalid or duplicate data" message.
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM suppliers WHERE phone = :phone", nativeQuery = true)
    boolean existsByPhoneIncludingDeleted(@Param("phone") String phone);

    /** The email column is unique too, and a tombstone keeps its value. Same reasoning as above. */
    @Query(value = "SELECT COUNT(*) > 0 FROM suppliers WHERE email = :email", nativeQuery = true)
    boolean existsByEmailIncludingDeleted(@Param("email") String email);
}
