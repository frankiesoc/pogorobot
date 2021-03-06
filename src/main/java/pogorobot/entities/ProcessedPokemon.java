/**
 Copyright 2019 Benjamin Marstaller
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 
    http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

package pogorobot.entities;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "\"ProcessedPokemon\"")
public class ProcessedPokemon implements Persistable<String> {

	public ProcessedPokemon() {
		this(null);
	}

	public ProcessedPokemon(String encounterId) {
		super();
		this.id = encounterId;
	}

	private static final long serialVersionUID = 887237883173573779L;

	@Id
	@Column(length = 32)
	String id;

	// public String getEncounterId() {
	// return id;
	// }
	//
	// public void setEncounterId(String id) {
	// this.encounterId = id;
	// }

	@Override
	public String toString() {
		return "ProcessedPokemon [" + (id != null ? "id=" + id : "") + "]";
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	@Transient
	public boolean isNew() {
		return id == null;
	}

}
