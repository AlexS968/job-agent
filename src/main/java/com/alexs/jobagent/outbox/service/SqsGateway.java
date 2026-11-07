package com.alexs.jobagent.outbox.service;

import com.alexs.jobagent.outbox.dto.SqsMessageDTO;
import com.alexs.jobagent.outbox.dto.SqsQueueBacklogDTO;
import java.util.List;

public interface SqsGateway {
    void send(String body);
    List<SqsMessageDTO> receive();
    void delete(String receiptHandle);
    SqsQueueBacklogDTO backlog();
}
