package vn.nploi;

import java.time.OffsetDateTime;

/** One row of the orders table. */
public record Order(long id, long customerId, OffsetDateTime createdAt, String status, long totalCents) {
}
