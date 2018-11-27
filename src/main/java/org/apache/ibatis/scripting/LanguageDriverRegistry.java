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
package org.apache.ibatis.scripting;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class LanguageDriverRegistry {

  //Class和对应LanguageDriver映射
  private final Map<Class<? extends LanguageDriver>, LanguageDriver> LANGUAGE_DRIVER_MAP = new HashMap<>();

  //默认语言驱动
  private Class<? extends LanguageDriver> defaultDriverClass;

  /**
   * 注册语言驱动，放入Map集合中
   * @param cls
   */
  public void register(Class<? extends LanguageDriver> cls) {
    if (cls == null) {
      throw new IllegalArgumentException("null is not a valid Language Driver");
    }
    if (!LANGUAGE_DRIVER_MAP.containsKey(cls)) {
      try {
        LANGUAGE_DRIVER_MAP.put(cls, cls.newInstance());
      } catch (Exception ex) {
        throw new ScriptingException("Failed to load language driver for " + cls.getName(), ex);
      }
    }
  }

  public void register(LanguageDriver instance) {
    if (instance == null) {
      throw new IllegalArgumentException("null is not a valid Language Driver");
    }
    Class<? extends LanguageDriver> cls = instance.getClass();
    if (!LANGUAGE_DRIVER_MAP.containsKey(cls)) {
      LANGUAGE_DRIVER_MAP.put(cls, instance);
    }
  }
  
  public LanguageDriver getDriver(Class<? extends LanguageDriver> cls) {
    return LANGUAGE_DRIVER_MAP.get(cls);
  }

  public LanguageDriver getDefaultDriver() {
    return getDriver(getDefaultDriverClass());
  }

  public Class<? extends LanguageDriver> getDefaultDriverClass() {
    return defaultDriverClass;
  }

  /**
   * 设置默认语言驱动，如果配置的话就按照Config.xml中配置的默认语言驱动，否则默认XmlLanguageDriver
   * @param defaultDriverClass
   */
  public void setDefaultDriverClass(Class<? extends LanguageDriver> defaultDriverClass) {
    register(defaultDriverClass);
    this.defaultDriverClass = defaultDriverClass;
  }

}
