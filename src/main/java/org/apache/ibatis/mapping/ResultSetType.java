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
package org.apache.ibatis.mapping;

import java.sql.ResultSet;

/**
 * @author Clinton Begin
 */
public enum ResultSetType {
  /**
   * behavior with same as unset (driver dependent).
   *
   * @since 3.5.0
   */
  DEFAULT(-1),
  /**
   * 结果集游标只能依次往下走，不支持跳查和回查等
   */
  FORWARD_ONLY(ResultSet.TYPE_FORWARD_ONLY),
  /**
   * 支持各种跳查、回查等，但对数据不敏感，其他session对于数据库的改变
   * 对于本次查询不可知
   */
  SCROLL_INSENSITIVE(ResultSet.TYPE_SCROLL_INSENSITIVE),
  /**
   * 对数据敏感，支持跳查、回查
   */
  SCROLL_SENSITIVE(ResultSet.TYPE_SCROLL_SENSITIVE);

  private final int value;

  ResultSetType(int value) {
    this.value = value;
  }

  public int getValue() {
    return value;
  }
}
