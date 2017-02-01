package com.olegych.scastie
package web

import org.scalatest.{FunSuite, Assertion}

import scala.collection.immutable.Queue

trait LoadBalancerTestUtils extends FunSuite with TestUtils {
  type TestLoadBalancer = LoadBalancer[String, String]

  def add(balancer: TestLoadBalancer, config: String): TestLoadBalancer = {
    val (_, balancer0) = balancer.add(Record(config, nextIp))
    balancer0
  }

  def assertConfigs(balancer: TestLoadBalancer)(columns: Seq[String]*): Assertion = {
    assertMultiset(
      balancer.servers.map(_.tailConfig),
      columns.flatten
    )
  }

  def assertMultiset[T: Ordering](xs: Seq[T], ys: Seq[T]): Assertion =
    assert(Multiset(xs) == Multiset(ys))

  var serverId = 0
  def server(config: String) = {
    val t = Server("s" + serverId, config)
    serverId += 1
    t
  }

  def servers(columns: Seq[String]*): Vector[Server[String, String]] = {
    columns.to[Vector].flatten.map(server)
  }

  var currentIp = 0
  def nextIp = {
    val t = Ip("ip" + currentIp)
    currentIp += 1
    t
  }

  def history(columns: Seq[String]*): History[String] = {
    val records =
      columns.to[Vector].flatten.map(config => Record(config, nextIp)).reverse

    History(Queue(records: _*))
  }
}
