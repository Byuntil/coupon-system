package cloud.coupon.domain.coupon.util;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.stereotype.Component;

@Component
public class CouponCodeGenerator implements CodeGenerator {
    @Override
    public String generateCode() {
        //대문자 영문 + 숫자 조합으로 8자리 생성 ex) KB9MP4NQ
        return RandomStringUtils.randomAlphanumeric(8).toUpperCase();
    }
}
