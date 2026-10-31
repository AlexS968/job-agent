FROM python:3.13-slim-bookworm@sha256:281d39ff02e3b389401586134ff9e6572954687822a58055ec07a60ded4e0b7f
WORKDIR /opt/bootstrap
COPY infra/bootstrap/requirements.txt .
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt \
    && groupadd --system --gid 10001 bootstrap \
    && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin bootstrap
COPY --chown=10001:10001 infra/bootstrap/bootstrap.py ./bootstrap.py
COPY --chown=10001:10001 infra/certs/us-east-1-bundle.pem ./certs/us-east-1-bundle.pem
USER 10001:10001
ENTRYPOINT ["python", "/opt/bootstrap/bootstrap.py"]
CMD ["prepare"]
