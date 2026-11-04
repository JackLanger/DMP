package de.dmp.server.data;


import jakarta.persistence.Column;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.mongodb.core.mapping.Document;


@Getter
@Setter
@Document(collection = "tags")
public class Tag {

  @Id
  @Column(name = "name", nullable = false)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  private String name;


}
