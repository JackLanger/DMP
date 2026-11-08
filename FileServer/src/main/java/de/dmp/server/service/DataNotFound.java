package de.dmp.server.service;

import de.dmp.server.data.Metadata;

class DataNotFound extends RuntimeException {

  public <T>DataNotFound(T fileId, Class<?> metaClass) {
    super(metaClass.getName() + " not found: " + fileId);
  }
}