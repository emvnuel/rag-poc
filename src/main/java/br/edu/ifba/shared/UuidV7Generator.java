package br.edu.ifba.shared;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.enhanced.SequenceStyleGenerator;

public class UuidV7Generator extends SequenceStyleGenerator {

    @Override
    public Object generate(final SharedSessionContractImplementor session, final Object object) {
        return UuidUtils.randomV7();
    }
}
