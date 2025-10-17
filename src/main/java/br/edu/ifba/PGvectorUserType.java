package br.edu.ifba;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import com.pgvector.PGvector;

public class PGvectorUserType implements UserType<PGvector> {

    @Override
    public int getSqlType() {
        return Types.OTHER;
    }

    @Override
    public Class<PGvector> returnedClass() {
        return PGvector.class;
    }

    @Override
    public boolean equals(PGvector x, PGvector y) {
        return x == null ? y == null : x.equals(y);
    }

    @Override
    public int hashCode(PGvector x) {
        return x == null ? 0 : x.hashCode();
    }

    @Override
    public PGvector nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session, Object owner) throws SQLException {
        String value = rs.getString(position);
        if (value == null) return null;
        try {
            return new PGvector(value);
        } catch (SQLException e) {
            throw new RuntimeException("Error reading PGvector from database", e);
        }
    }

    @Override
    public void nullSafeSet(PreparedStatement st, PGvector value, int index, SharedSessionContractImplementor session) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, value);
        }
    }

    @Override
    public PGvector deepCopy(PGvector value) {
        if (value == null) return null;
        try {
            return new PGvector(value.toString());
        } catch (SQLException e) {
            throw new RuntimeException("Error copying PGvector", e);
        }
    }

    @Override
    public boolean isMutable() {
        return false;
    }

    @Override
    public Serializable disassemble(PGvector value) {
        return value == null ? null : value.toString();
    }

    @Override
    public PGvector assemble(Serializable cached, Object owner) {
        if (cached == null) return null;
        try {
            return new PGvector((String) cached);
        } catch (SQLException e) {
            throw new RuntimeException("Error assembling PGvector", e);
        }
    }
}
