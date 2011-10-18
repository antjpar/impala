// Copyright (c) 2011 Cloudera, Inc. All rights reserved.

package com.cloudera.impala.testutil;

import java.util.List;
import java.util.UUID;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.thrift.TException;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TSimpleServer;
import org.apache.thrift.server.TServer.Args;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;

import com.cloudera.impala.analysis.AnalysisContext;
import com.cloudera.impala.analysis.Expr;
import com.cloudera.impala.catalog.Catalog;
import com.cloudera.impala.catalog.PrimitiveType;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.common.NotImplementedException;
import com.cloudera.impala.planner.PlanNode;
import com.cloudera.impala.planner.Planner;
import com.cloudera.impala.thrift.ImpalaPlanService;
import com.cloudera.impala.thrift.TQueryExecRequest;
import com.cloudera.impala.thrift.TUniqueId;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

// Service to construct a TPlanExecRequest for a given query string.
// We're implementing that as a stand-alone service, rather than having
// the backend call the corresponding Coordinator function, because
// the process would crash somewhere during metastore setup when running
// under gdb.
public class PlanService {
  public static class PlanServiceHandler implements ImpalaPlanService.Iface {
    private final Catalog catalog;
    private int nextQueryId;

    public PlanServiceHandler(Catalog catalog) {
      this.catalog = catalog;
      this.nextQueryId = 0;
    }

    public TQueryExecRequest GetExecRequest(String stmt, int numNodes) throws TException {
      System.out.println("Executing '" + stmt + "'");
      AnalysisContext analysisCtxt = new AnalysisContext(catalog);
      AnalysisContext.AnalysisResult analysisResult = null;
      try {
        analysisResult = analysisCtxt.analyze(stmt);
      } catch (AnalysisException e) {
        System.out.println(e.getMessage());
        throw new TException(e.getMessage());
      }
      Preconditions.checkNotNull(analysisResult.selectStmt);

      // populate colTypes
      List<PrimitiveType> colTypes = Lists.newArrayList();
      for (Expr expr : analysisResult.selectStmt.getSelectListExprs()) {
        colTypes.add(expr.getType());
      }

      // create plan
      Planner planner = new Planner();

      List<PlanNode> planFragments = Lists.newArrayList();
      TQueryExecRequest request;
      try {
        request = planner.createPlanFragments(
            analysisResult.selectStmt, analysisResult.analyzer, numNodes, planFragments);
      } catch (NotImplementedException e) {
        throw new TException(e.getMessage());
      } catch (InternalException e) {
        throw new TException(e.getMessage());
      }

      UUID queryId = new UUID(nextQueryId++, 0);
      request.setQueryId(
          new TUniqueId(queryId.getMostSignificantBits(),
                        queryId.getLeastSignificantBits()));
      request.setAsAscii(false);
      request.setAbortOnError(false);
      request.setMaxErrors(100);
      request.setBatchSize(0);

      for (int i = 0; i < planFragments.size(); ++i) {
        if (i > 0) {
          System.out.println("----");
        }
        System.out.println(planFragments.get(i).getExplainString());
      }

      System.out.println("returned exec request: " + request.toString());
      return request;
    }

    public void ShutdownServer() {
      System.exit(0);
    }
  }

  public static void main(String[] args) {
    HiveMetaStoreClient client = null;
    try {
      client = new HiveMetaStoreClient(new HiveConf(PlanService.class));
      Catalog catalog = new Catalog(client);

      PlanServiceHandler handler = new PlanServiceHandler(catalog);
      ImpalaPlanService.Processor proc = new ImpalaPlanService.Processor(handler);
      TServerTransport transport = new TServerSocket(20000);
      TServer server = new TSimpleServer(new Args(transport).processor(proc));
      server.serve();
    } catch (Exception e) {
      System.err.println(e.getMessage());
      System.exit(2);
    }
  }

}
