const databaseName = 'family-care:mock:document-files'

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(databaseName, 1)

    request.onupgradeneeded = () => {
      request.result.createObjectStore('files')
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
    request.onblocked = () => reject(new Error('문서 저장소를 열지 못했어요.'))
  })
}

async function runFileStore(mode, operation) {
  const database = await openDatabase()

  return new Promise((resolve, reject) => {
    const transaction = database.transaction('files', mode)
    const request = operation(transaction.objectStore('files'))

    transaction.oncomplete = () => {
      database.close()
      resolve(request.result)
    }
    transaction.onabort = () => {
      database.close()
      reject(transaction.error || request.error || new Error('문서를 저장하지 못했어요.'))
    }
    transaction.onerror = () => {
      database.close()
      reject(transaction.error || request.error || new Error('문서를 저장하지 못했어요.'))
    }
  })
}

export function saveMedicalDocumentFile(encounterId, file) {
  return runFileStore('readwrite', (store) => store.put(file, encounterId))
}

export function getMedicalDocumentFile(encounterId) {
  return runFileStore('readonly', (store) => store.get(encounterId))
}

export function removeMedicalDocumentFile(encounterId) {
  return runFileStore('readwrite', (store) => store.delete(encounterId))
}
