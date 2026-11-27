use std::ops::Index;

use rustc_hash::FxHashMap;

use super::Craft;

/// The crafts of a world by their ids, next to each other in memory.
#[derive(Default)]
pub(super) struct Crafts {
    places: FxHashMap<i64, usize>,
    list: Vec<Craft>,
}

impl Crafts {
    pub(super) fn insert(&mut self, craft: Craft) {
        self.places.insert(craft.id, self.list.len());
        self.list.push(craft);
    }

    pub(super) fn remove(&mut self, id: i64) -> Option<Craft> {
        let place = self.places.remove(&id)?;
        let craft = self.list.swap_remove(place);
        if let Some(moved) = self.list.get(place) {
            self.places.insert(moved.id, place);
        }
        Some(craft)
    }

    pub(super) fn get(&self, id: &i64) -> Option<&Craft> {
        Some(&self.list[*self.places.get(id)?])
    }

    pub(super) fn get_mut(&mut self, id: &i64) -> Option<&mut Craft> {
        Some(&mut self.list[*self.places.get(id)?])
    }

    pub(super) fn len(&self) -> usize {
        self.list.len()
    }

    #[cfg(test)]
    pub(super) fn is_empty(&self) -> bool {
        self.list.is_empty()
    }

    pub(super) fn values(&self) -> impl Iterator<Item = &Craft> {
        self.list.iter()
    }

    pub(super) fn as_mut_slice(&mut self) -> &mut [Craft] {
        &mut self.list
    }
}

impl Index<&i64> for Crafts {
    type Output = Craft;

    fn index(&self, id: &i64) -> &Craft {
        &self.list[self.places[id]]
    }
}

impl<'a> IntoIterator for &'a Crafts {
    type Item = (&'a i64, &'a Craft);
    type IntoIter =
        std::iter::Map<std::slice::Iter<'a, Craft>, fn(&'a Craft) -> (&'a i64, &'a Craft)>;

    fn into_iter(self) -> Self::IntoIter {
        self.list.iter().map(|c| (&c.id, c))
    }
}
