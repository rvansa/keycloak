#!/bin/bash

. ./common.sh
OPENSHIFT_PROJECT=${OPENSHIFT_PROJECT:-keycloak-test}
AUTH_TOKEN=${AUTH_TOKEN:-$(oc whoami -t)}
START_TIME=$(cat $PROJECT_BUILD_DIRECTORY/test.start.timestamp)
END_TIME=$(cat $PROJECT_BUILD_DIRECTORY/test.end.timestamp)
STEP_TIME=$(( ($END_TIME - $START_TIME + 10799) / 10800))

while read PROMETHEUS_QUERY OUTPUT_FILE REST_OF_LINE
do
   if [ -z "$PROMETHEUS_QUERY" ]; then
       continue;
   fi
   if [ -n "$REST_OF_LINE" ]; then
       echo "Invalid line (too many items): $PROMETHEUS_QUERY $OUTPUT_FILE $REST_OF_LINE"
       continue;
   fi
   QUERY_URL=$PROMETHEUS_URL'/api/v1/query_range?query='$PROMETHEUS_QUERY'&start='$START_TIME'&end='$END_TIME'&step='$STEP_TIME
   curl -kgsS -H 'Authorization: Bearer '$AUTH_TOKEN -o /tmp/prometheus.tmp $QUERY_URL
   jq '.data.result[].metric' < /tmp/prometheus.tmp > /tmp/prometheus.metric.tmp
   RESULTS=$(jq -s 'length' < /tmp/prometheus.metric.tmp)

   for RESULT in $(seq 0 $RESULTS | head -n -1); do
       RESULT_FILE=$OUTPUT_FILE
       for NAME in $(jq -r -s '.['$RESULT'] | keys[]' < /tmp/prometheus.metric.tmp); do
            VALUE=$(jq -r -s '.['$RESULT'].'$NAME < /tmp/prometheus.metric.tmp)
            RESULT_FILE=$(echo $RESULT_FILE | sed 's#\${'$NAME'}#'$VALUE'#g')
       done
       jq -r '.data.result['$RESULT'].values[] | [.[0], .[1] | tonumber] | @csv' < /tmp/prometheus.tmp > $OUTPUT_DIR/$RESULT_FILE

   done
done < <(grep -v -e '^ *#' $PROMETHEUS_CONFIG | sed 's/\${OPENSHIFT_PROJECT}/'$OPENSHIFT_PROJECT'/g')