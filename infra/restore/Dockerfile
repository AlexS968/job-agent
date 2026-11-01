FROM python:3.13-alpine3.24@sha256:7984cf56ceaddebe4fefeb75d64000b9f45d8b77b11a09b24524da6cdc3af0c8
WORKDIR /opt/restore
COPY infra/restore/requirements.txt .
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt \
    && addgroup -S -g 10001 verifier \
    && adduser -S -D -H -u 10001 -G verifier -s /bin/false verifier
COPY --chown=10001:10001 infra/restore/verify.py ./verify.py
COPY --chown=10001:10001 infra/certs/us-east-1-bundle.pem ./certs/us-east-1-bundle.pem
USER 10001:10001
ENTRYPOINT ["python", "/opt/restore/verify.py"]
