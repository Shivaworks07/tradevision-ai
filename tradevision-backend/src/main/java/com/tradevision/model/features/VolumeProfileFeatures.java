package com.tradevision.model.features;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Volume Profile: POC, VAH, VAL, HVN, LVN */
@Data @NoArgsConstructor
public class VolumeProfileFeatures {
    String  location;
    double  poc;
    double  vah;
    double  val;
    double  pocDistancePct;
    boolean nearHVN;
    boolean nearLVN;
    String  vpBias;
}
