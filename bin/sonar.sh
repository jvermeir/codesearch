docker rm -f sonar-codesearch 2>/dev/null || true
docker run -d --name sonar-codesearch -e SONAR_ES_BOOTSTRAP_CHECKS_DISABLE=true -p 9000:9000 \
  -v "$(pwd)/data/sonarqube_data:/opt/sonarqube/data" \
  -v "$(pwd)/data/sonarqube_logs:/opt/sonarqube/logs" \
  -v "$(pwd)/data/sonarqube_extensions:/opt/sonarqube/extensions" \
  sonarqube:community
