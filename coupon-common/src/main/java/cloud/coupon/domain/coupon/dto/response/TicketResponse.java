package cloud.coupon.domain.coupon.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TicketResponse {
    private String ticketId;
    private TicketStatus status;
    private String issuedCode;
    private String message;
    private String subscribeUrl;

    public static TicketResponse pending(String ticketId) {
        return TicketResponse.builder()
                .ticketId(ticketId)
                .status(TicketStatus.PENDING)
                .subscribeUrl("/api/v2/coupons/subscribe/" + ticketId)
                .build();
    }

    public static TicketResponse completed(String ticketId, String issuedCode) {
        return TicketResponse.builder()
                .ticketId(ticketId)
                .status(TicketStatus.COMPLETED)
                .issuedCode(issuedCode)
                .message("쿠폰이 발급되었습니다.")
                .build();
    }

    public static TicketResponse failed(String ticketId, String message) {
        return TicketResponse.builder()
                .ticketId(ticketId)
                .status(TicketStatus.FAILED)
                .message(message)
                .build();
    }

}
