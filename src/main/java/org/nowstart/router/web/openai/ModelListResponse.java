package org.nowstart.router.web.openai;

import java.util.List;

public record ModelListResponse(
        String object,
        List<ModelDescriptor> data
) {

    public record ModelDescriptor(
            String id,
            String object,
            long created,
            String owned_by
    ) {
    }
}
