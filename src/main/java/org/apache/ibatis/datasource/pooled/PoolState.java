/**
 *    Copyright 2009-2018 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.datasource.pooled;

import java.util.ArrayList;
import java.util.List;

/**
 * 线程池状态对象，用于连接池的一些信息记录和统计，状态检测等
 * 一个PoolState对应一个PooledDataSource，一个PooledDataSource包含多个PooledConnection
 * @author Clinton Begin
 */
public class PoolState {

	//一一对应的连接池数据源
	protected PooledDataSource dataSource;
	//空闲连接列表
	protected final List<PooledConnection> idleConnections = new ArrayList<>();
	//活跃连接列表
	protected final List<PooledConnection> activeConnections = new ArrayList<>();
	//连接请求次数
	protected long requestCount = 0;
	//累计请求时长
	protected long accumulatedRequestTime = 0;
	//累计强制回收次数
	protected long accumulatedCheckoutTime = 0;
	//连接超时次数
	protected long claimedOverdueConnectionCount = 0;
	protected long accumulatedCheckoutTimeOfOverdueConnections = 0;
	//累计等待时间
	protected long accumulatedWaitTime = 0;
	//等待次数
	protected long hadToWaitCount = 0;
	//问题连接数
	protected long badConnectionCount = 0;

	public PoolState(PooledDataSource dataSource) {
		this.dataSource = dataSource;
	}

	public synchronized long getRequestCount() {
		return requestCount;
	}

	public synchronized long getAverageRequestTime() {
		return requestCount == 0 ? 0 : accumulatedRequestTime / requestCount;
	}

	public synchronized long getAverageWaitTime() {
		return hadToWaitCount == 0 ? 0 : accumulatedWaitTime / hadToWaitCount;

	}

	public synchronized long getHadToWaitCount() {
		return hadToWaitCount;
	}

	public synchronized long getBadConnectionCount() {
		return badConnectionCount;
	}

	public synchronized long getClaimedOverdueConnectionCount() {
		return claimedOverdueConnectionCount;
	}

	public synchronized long getAverageOverdueCheckoutTime() {
		return claimedOverdueConnectionCount == 0 ? 0
			: accumulatedCheckoutTimeOfOverdueConnections / claimedOverdueConnectionCount;
	}

	public synchronized long getAverageCheckoutTime() {
		return requestCount == 0 ? 0 : accumulatedCheckoutTime / requestCount;
	}


	public synchronized int getIdleConnectionCount() {
		return idleConnections.size();
	}

	public synchronized int getActiveConnectionCount() {
		return activeConnections.size();
	}

	@Override
	public synchronized String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append("\n===CONFINGURATION==============================================");
		builder.append("\n jdbcDriver                     ").append(dataSource.getDriver());
		builder.append("\n jdbcUrl                        ").append(dataSource.getUrl());
		builder.append("\n jdbcUsername                   ").append(dataSource.getUsername());
		builder.append("\n jdbcPassword                   ")
			.append((dataSource.getPassword() == null ? "NULL" : "************"));
		builder.append("\n poolMaxActiveConnections       ")
			.append(dataSource.poolMaximumActiveConnections);
		builder.append("\n poolMaxIdleConnections         ")
			.append(dataSource.poolMaximumIdleConnections);
		builder.append("\n poolMaxCheckoutTime            ")
			.append(dataSource.poolMaximumCheckoutTime);
		builder.append("\n poolTimeToWait                 ").append(dataSource.poolTimeToWait);
		builder.append("\n poolPingEnabled                ").append(dataSource.poolPingEnabled);
		builder.append("\n poolPingQuery                  ").append(dataSource.poolPingQuery);
		builder.append("\n poolPingConnectionsNotUsedFor  ")
			.append(dataSource.poolPingConnectionsNotUsedFor);
		builder.append("\n ---STATUS-----------------------------------------------------");
		builder.append("\n activeConnections              ").append(getActiveConnectionCount());
		builder.append("\n idleConnections                ").append(getIdleConnectionCount());
		builder.append("\n requestCount                   ").append(getRequestCount());
		builder.append("\n averageRequestTime             ").append(getAverageRequestTime());
		builder.append("\n averageCheckoutTime            ").append(getAverageCheckoutTime());
		builder.append("\n claimedOverdue                 ")
			.append(getClaimedOverdueConnectionCount());
		builder.append("\n averageOverdueCheckoutTime     ")
			.append(getAverageOverdueCheckoutTime());
		builder.append("\n hadToWait                      ").append(getHadToWaitCount());
		builder.append("\n averageWaitTime                ").append(getAverageWaitTime());
		builder.append("\n badConnectionCount             ").append(getBadConnectionCount());
		builder.append("\n===============================================================");
		return builder.toString();
	}

}
