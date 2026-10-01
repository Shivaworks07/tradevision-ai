package com.tradevision.repository;

import com.tradevision.model.OtpRecord;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;
import java.util.Optional;

public interface OtpRepository extends MongoRepository<OtpRecord, String> {
    Optional<OtpRecord> findTopByMobileAndPurposeAndUsedFalseOrderByCreatedAtDesc(String mobile, String purpose);
    List<OtpRecord> findByMobileAndPurposeOrderByCreatedAtDesc(String mobile, String purpose);
    long deleteByMobileAndPurpose(String mobile, String purpose);
}
