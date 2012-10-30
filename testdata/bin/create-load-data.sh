#!/bin/bash
# Copyright (c) 2012 Cloudera, Inc. All rights reserved.

if [ x${JAVA_HOME} == x ]; then
  echo JAVA_HOME not set
  exit 1
fi
. ${IMPALA_HOME}/bin/impala-config.sh
set -u
set -e

# Load the data set
pushd ${IMPALA_HOME}/bin
./start-impala-cluster.py -s 1
sleep 5
./load-data.py --workloads functional-query --exploration_strategy exhaustive
./load-data.py --workloads tpch --exploration_strategy core
./start-impala-cluster.py --kill_only
popd

# TODO: The multi-format table will move these files. So we need to copy them to a
# temporary location for that table to use. Should find a better way to handle this.
echo COPYING DATA FOR DEPENDENT TABLES
hadoop fs -rm -r -f /test-warehouse/alltypesmixedformat
hadoop fs -rm -r -f /tmp/alltypes_rc
hadoop fs -rm -r -f /tmp/alltypes_seq
hadoop fs -mkdir -p /tmp/alltypes_seq/year=2009
hadoop fs -mkdir -p /tmp/alltypes_rc/year=2009
hadoop fs -cp  /test-warehouse/alltypes_seq/year=2009/month=2/ /tmp/alltypes_seq/year=2009
hadoop fs -cp  /test-warehouse/alltypes_rc/year=2009/month=3/ /tmp/alltypes_rc/year=2009
hadoop fs -cp  /test-warehouse/alltypes_trevni/year=2009/month=4/ \
  /tmp/alltypes_rc/year=2009


# Make compressed data for alltypesaggsmultifiles
rm -rf /tmp/alltypesaggmultifiles
hadoop fs -get /test-warehouse/alltypesaggmultifiles /tmp
(cd /tmp/alltypesaggmultifiles; lzop */*/*/*)

# Create lzo compressed versions of the Alltypes data files.
(cd ${IMPALA_HOME}/testdata/target/AllTypes; rm -f *.lzo; lzop *.txt)

# For tables that rely on loading data from local fs test-warehouse
# TODO: Find a good way to integrate this with the normal data loading scripts
${HIVE_HOME}/bin/hive -hiveconf hive.root.logger=WARN,console -v \
  -f ${IMPALA_HOME}/testdata/bin/load-dependent-tables.sql
if [ $? != 0 ]; then
  echo DEPENDENT LOAD FAILED
  exit 1
fi

# create the index files for AllTypes_lzo.
${IMPALA_HOME}/testdata/bin/lzo_indexer.sh /test-warehouse/alltypes_lzo
${IMPALA_HOME}/testdata/bin/lzo_indexer.sh /test-warehouse/alltypesaggmultifiles_lzo

# Load the index files for lzo data.
hadoop fs -rm -f /test-warehouse/bad_text_lzo//bad_text.lzo.index
hadoop fs -put ${IMPALA_HOME}/testdata/bad_text_lzo/bad_text.lzo.index \
  /test-warehouse/bad_text_lzo/

# When we want to start computing table stats after data loading uncomment this line
# python ${IMPALA_HOME}/tests/util/compute_table_stats.py
